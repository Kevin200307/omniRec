// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.commerce.model.CommerceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Posts events to the collector with the JDK's own HTTP client: no Spring, no
 * extra dependencies.
 *
 * Asynchronous by default. A daemon worker drains a bounded queue in batches,
 * so a slow or unreachable collector never blocks the request that tracked the
 * event. Transient failures are retried with exponential backoff and full
 * jitter; permanent rejections are logged and dropped.
 */
public class JdkHttpEventSender implements EventSender, BatchDelivery, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpEventSender.class);

    /** Settings. {@code apiKey} may be null for a collector in open mode. */
    public record Options(
            String endpoint,
            String apiKey,
            boolean async,
            int queueCapacity,
            int maxBatchSize,
            int maxRetries,
            Duration retryInitialInterval,
            Duration retryMaxInterval,
            Duration requestTimeout
    ) {
        public static Options defaults(String endpoint, String apiKey) {
            return new Options(endpoint, apiKey, true, 10_000, 50, 8, Duration.ofMillis(500), Duration.ofSeconds(30),
                    Duration.ofSeconds(10));
        }

        public Options synchronous() {
            return new Options(endpoint, apiKey, false, queueCapacity, maxBatchSize, maxRetries, retryInitialInterval,
                    retryMaxInterval, requestTimeout);
        }

        public Options withRetries(int retries, Duration initial) {
            return new Options(endpoint, apiKey, async, queueCapacity, maxBatchSize, retries, initial,
                    retryMaxInterval, requestTimeout);
        }
    }

    private final Options options;
    private final URI batchUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final BlockingQueue<CommerceEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    public JdkHttpEventSender(Options options) {
        this(options, HttpClient.newBuilder().connectTimeout(options.requestTimeout()).build());
    }

    JdkHttpEventSender(Options options, HttpClient http) {
        if (options.endpoint() == null || !options.endpoint().matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("endpoint must be an absolute http(s) URL");
        }
        this.options = options;
        this.batchUri = URI.create(options.endpoint().replaceAll("/$", "") + "/v1/events/batch");
        this.http = http;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        this.queue = new LinkedBlockingQueue<>(options.queueCapacity());
        if (options.async()) {
            this.worker = new Thread(this::drainLoop, "omnirec-event-sender");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.worker = null;
        }
    }

    @Override
    public void send(CommerceEvent event) {
        if (!options.async()) {
            deliver(List.of(event));
            return;
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            log.warn("Event queue is full ({}); dropping event {}. The collector may be unreachable.",
                    options.queueCapacity(), event.eventId());
        }
    }

    @Override
    public void flush() {
        List<CommerceEvent> batch = new ArrayList<>();
        while (queue.drainTo(batch, options.maxBatchSize()) > 0) {
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
                queue.drainTo(batch, options.maxBatchSize() - 1);
                deliver(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // One bad batch must never stop delivery for the life of the app.
                log.warn("Event delivery failed: {}", e.getMessage());
            }
        }
    }

    /** Sends a batch, retrying transient failures with backoff. */
    void deliver(List<CommerceEvent> events) {
        for (int attempt = 0; ; attempt++) {
            Outcome outcome = deliverOnce(events);
            if (outcome == Outcome.DELIVERED) return;
            if (outcome == Outcome.REJECTED || attempt >= options.maxRetries()) {
                dropped.addAndGet(events.size());
                if (outcome == Outcome.RETRYABLE) {
                    log.error("Giving up on {} event(s) after {} attempts", events.size(), attempt + 1);
                }
                return;
            }
            try {
                Thread.sleep(backoff(attempt).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    Duration backoff(int attempt) {
        long ceiling = Math.min(options.retryMaxInterval().toMillis(),
                options.retryInitialInterval().toMillis() * (1L << Math.min(attempt, 20)));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }

    @Override
    public Outcome deliverOnce(List<CommerceEvent> events) {
        if (events.isEmpty()) return Outcome.DELIVERED;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(batchUri)
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("events", events))));
            if (options.apiKey() != null && !options.apiKey().isBlank()) {
                request.header("X-Omnirec-Key", options.apiKey());
            }
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return Outcome.DELIVERED;
            if (status == 408 || status == 429 || status >= 500) {
                log.warn("Collector returned {} for {} event(s); will retry", status, events.size());
                return Outcome.RETRYABLE;
            }
            log.error("Collector permanently rejected {} event(s) with {}; not retrying", events.size(), status);
            return Outcome.REJECTED;
        } catch (IOException e) {
            log.warn("Could not reach the collector ({}); will retry", e.getMessage());
            return Outcome.RETRYABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.RETRYABLE;
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }
}
