// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.transport;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.tracker.BatchDelivery;
import io.omnirec.tracker.EventSender;
import io.omnirec.tracker.config.CommerceTrackerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POSTs backend events to the Event API.
 *
 * <h2>Asynchronous by default</h2>
 * A merchant calls {@code commerce.purchase.completed(...)} from inside their
 * order-placement path. If that blocked on an HTTP round trip, an outage in
 * <em>our</em> service would slow or fail <em>their</em> checkout. So events go
 * onto a bounded queue drained by a background worker; tracking must never be
 * able to break the transaction it observes.
 *
 * <h2>Retry</h2>
 * These are authoritative business events, so a transient failure is retried
 * with exponential backoff: 5xx, 408, 429, and network errors, up to
 * {@code max-retries} times. A 4xx other than those is permanent (bad key,
 * invalid payload) and resending identical bytes cannot fix it, so the batch is
 * dropped and logged. Every event carries its eventId across retries, so a
 * retry of a request that actually landed is deduplicated by the API.
 *
 * <h2>Limits, stated plainly</h2>
 * The queue is in memory and bounded. If the Event API is down for longer than
 * the retries cover, or the application restarts with events queued, those
 * events are lost; the drop counter makes that visible. Merchants who need
 * guaranteed delivery of purchases should record them in their own
 * transactional outbox and replay from it — the deterministic eventIds make any
 * replay safe.
 */
public class HttpEventSender implements EventSender, BatchDelivery, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpEventSender.class);

    /** How a send ended, for the retry loop. */
    enum Outcome { DELIVERED, RETRYABLE, REJECTED }

    /** Sleeps between retries; replaced in tests so they don't wait. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestTemplate restTemplate;
    private final CommerceTrackerProperties properties;
    private final Sleeper sleeper;
    private final BlockingQueue<CommerceEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    public HttpEventSender(RestTemplate restTemplate, CommerceTrackerProperties properties) {
        this(restTemplate, properties, duration -> Thread.sleep(duration.toMillis()));
    }

    HttpEventSender(RestTemplate restTemplate, CommerceTrackerProperties properties, Sleeper sleeper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.sleeper = sleeper;
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());

        if (properties.isAsync()) {
            this.worker = new Thread(this::drainLoop, "omnirec-event-sender");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.worker = null;
        }
    }

    @Override
    public void send(CommerceEvent event) {
        if (!properties.isAsync()) {
            deliver(List.of(event));
            return;
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            log.warn("Event queue is full ({}); dropping event {}. The Event API may be unreachable.",
                    properties.getQueueCapacity(), event.eventId());
        }
    }

    @Override
    public void flush() {
        List<CommerceEvent> batch = new ArrayList<>();
        while (queue.drainTo(batch, properties.getMaxBatchSize()) > 0) {
            deliver(batch);
            batch = new ArrayList<>();
        }
    }

    /** Events dropped since startup: queue overflow, permanent rejection, or retries exhausted. */
    public long droppedCount() {
        return dropped.get();
    }

    private void drainLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                CommerceEvent first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;

                List<CommerceEvent> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, properties.getMaxBatchSize() - 1);
                deliver(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Never let the worker die: one bad batch must not stop all
                // future delivery for the lifetime of the application.
                log.warn("Event delivery failed: {}", e.getMessage());
            }
        }
    }

    /** Sends a batch, retrying transient failures with exponential backoff. */
    void deliver(List<CommerceEvent> events) {
        for (int attempt = 0; ; attempt++) {
            Outcome outcome = post(events);
            if (outcome == Outcome.DELIVERED) return;
            if (outcome == Outcome.REJECTED) {
                dropped.addAndGet(events.size());
                return;
            }
            if (attempt >= properties.getMaxRetries()) {
                dropped.addAndGet(events.size());
                log.error("Giving up on {} event(s) after {} retries; the Event API is unreachable",
                        events.size(), properties.getMaxRetries());
                return;
            }
            try {
                sleeper.sleep(backoff(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropped.addAndGet(events.size());
                return;
            }
        }
    }

    Duration backoff(int attempt) {
        long initial = properties.getRetryInitialInterval().toMillis();
        long max = properties.getRetryMaxInterval().toMillis();
        long exponential = initial * (1L << Math.min(attempt, 30));
        return Duration.ofMillis(exponential <= 0 || exponential > max ? max : exponential);
    }

    @Override
    public BatchDelivery.Outcome deliverOnce(List<CommerceEvent> events) {
        return switch (post(events)) {
            case DELIVERED -> BatchDelivery.Outcome.DELIVERED;
            case RETRYABLE -> BatchDelivery.Outcome.RETRYABLE;
            case REJECTED -> BatchDelivery.Outcome.REJECTED;
        };
    }

    Outcome post(List<CommerceEvent> events) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            // Only needed when the collector runs in keys mode.
            headers.set("X-Omnirec-Key", properties.getApiKey());
        }

        Map<String, Object> body = Map.of("events", events);

        try {
            restTemplate.postForEntity(
                    properties.getEndpoint().replaceAll("/$", "") + "/v1/events/batch",
                    new HttpEntity<>(body, headers),
                    String.class);
            return Outcome.DELIVERED;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 408 || status == 429 || status >= 500) {
                log.warn("Event API returned {} for {} event(s); will retry", status, events.size());
                return Outcome.RETRYABLE;
            }
            log.error("Event API permanently rejected {} event(s) with {}; not retrying", events.size(), status);
            return Outcome.REJECTED;
        } catch (RestClientException e) {
            log.warn("Could not reach the Event API ({}); will retry", e.getMessage());
            return Outcome.RETRYABLE;
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        flush();
    }
}
