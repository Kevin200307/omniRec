// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RetrySchedule;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers {@code omnirec.queue.depth} gauges, tagged by destination and
 * queue kind (main, retry, dlq).
 *
 * Depth is read from the broker on scrape, but cached briefly per queue: a
 * Prometheus scrape reads every gauge, and hitting the broker once per queue
 * per scrape would turn monitoring into load.
 */
public class QueueDepthGauges {

    private static final Duration CACHE_FOR = Duration.ofSeconds(15);

    private final AmqpAdmin amqpAdmin;
    private final Clock clock;
    private final Map<String, CachedDepth> cache = new ConcurrentHashMap<>();

    private record CachedDepth(double value, Instant readAt) {
    }

    public QueueDepthGauges(MeterRegistry registry, AmqpAdmin amqpAdmin,
                            List<EventDestination> destinations, RetrySchedule retrySchedule) {
        this(registry, amqpAdmin, destinations, retrySchedule, Clock.systemUTC());
    }

    QueueDepthGauges(MeterRegistry registry, AmqpAdmin amqpAdmin,
                     List<EventDestination> destinations, RetrySchedule retrySchedule, Clock clock) {
        this.amqpAdmin = amqpAdmin;
        this.clock = clock;

        for (EventDestination destination : destinations) {
            String id = destination.id();
            register(registry, id, "main", QueueTopology.queueName(id));
            register(registry, id, "dlq", QueueTopology.deadLetterQueueName(id));
            for (int attempt = 1; attempt <= retrySchedule.maxRetries(); attempt++) {
                register(registry, id, "retry-" + attempt, QueueTopology.retryQueueName(id, attempt));
            }
        }
    }

    private void register(MeterRegistry registry, String destinationId, String kind, String queueName) {
        Gauge.builder("omnirec.queue.depth", () -> depth(queueName))
                .tag("destination", destinationId)
                .tag("queue", kind)
                .description("Messages waiting in the queue")
                .register(registry);
    }

    double depth(String queueName) {
        Instant now = clock.instant();
        CachedDepth cached = cache.get(queueName);
        if (cached != null && cached.readAt().plus(CACHE_FOR).isAfter(now)) {
            return cached.value();
        }
        double value;
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queueName);
            value = info == null ? Double.NaN : info.getMessageCount();
        } catch (RuntimeException e) {
            // Broker unreachable: report "unknown" rather than a misleading 0.
            value = Double.NaN;
        }
        cache.put(queueName, new CachedDepth(value, now));
        return value;
    }
}
