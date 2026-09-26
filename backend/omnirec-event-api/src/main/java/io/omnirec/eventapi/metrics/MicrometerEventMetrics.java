// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.commerce.metrics.EventMetrics;

/**
 * Binds the pipeline's counters to Micrometer.
 *
 * Tag cardinality is the thing to be careful with here: {@code tenantId} and
 * {@code destinationId} are bounded by configuration, and {@code reason} is
 * drawn from a fixed vocabulary. Nothing per-event — an eventId or productId as
 * a tag would create a time series per event and take the metrics backend down.
 * Per-event correlation belongs in structured logs, not in metrics.
 */
public class MicrometerEventMetrics implements EventMetrics {

    private final MeterRegistry registry;

    public MicrometerEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void eventsReceived(String tenantId, int count) {
        increment("omnirec.events.received", count, "tenant", tenant(tenantId));
    }

    @Override
    public void eventsValidated(String tenantId, int count) {
        increment("omnirec.events.validated", count, "tenant", tenant(tenantId));
    }

    @Override
    public void eventsRejected(String tenantId, String reason, int count) {
        increment("omnirec.events.rejected", count, "tenant", tenant(tenantId), "reason", reason);
    }

    @Override
    public void eventsQueued(String tenantId, int count) {
        increment("omnirec.events.queued", count, "tenant", tenant(tenantId));
    }

    @Override
    public void eventsProcessed(String tenantId, int count) {
        increment("omnirec.events.processed", count, "tenant", tenant(tenantId));
    }

    @Override
    public void eventsFailed(String tenantId, String reason, int count) {
        increment("omnirec.events.failed", count, "tenant", tenant(tenantId), "reason", reason);
    }

    @Override
    public void duplicateEvents(String tenantId, String stage, int count) {
        increment("omnirec.events.duplicates", count, "tenant", tenant(tenantId), "stage", stage);
    }

    @Override
    public void providerDeliverySuccess(String destinationId, int count) {
        increment("omnirec.provider.delivery.success", count, "destination", destinationId);
    }

    @Override
    public void providerDeliveryFailure(String destinationId, String reason, int count) {
        increment("omnirec.provider.delivery.failure", count, "destination", destinationId, "reason", reason);
    }

    @Override
    public void retryScheduled(String destinationId, int attempt) {
        increment("omnirec.provider.retries", 1, "destination", destinationId, "attempt", String.valueOf(attempt));
    }

    @Override
    public void deadLettered(String destinationId, String reason) {
        increment("omnirec.provider.dead_lettered", 1, "destination", destinationId, "reason", reason);
    }

    @Override
    public void deliveryLatency(String destinationId, java.time.Duration latency) {
        if (latency.isNegative()) return;
        registry.timer("omnirec.provider.delivery.latency", "destination", destinationId).record(latency);
    }

    private void increment(String name, int count, String... tags) {
        if (count <= 0) return;
        registry.counter(name, tags).increment(count);
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "unknown" : tenantId;
    }
}
