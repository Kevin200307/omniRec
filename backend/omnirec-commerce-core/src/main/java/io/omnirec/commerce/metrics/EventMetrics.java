package io.omnirec.commerce.metrics;

/**
 * The pipeline's counters, as a narrow interface so omnirec-commerce-core stays
 * free of a metrics-library dependency. The Event API binds this to Micrometer;
 * tests use a recording fake; {@link #noop()} is the fallback when no registry
 * is present.
 *
 * Counter names match docs/observability section of the README.
 */
public interface EventMetrics {

    void eventsReceived(String tenantId, int count);

    void eventsValidated(String tenantId, int count);

    void eventsRejected(String tenantId, String reason, int count);

    void eventsQueued(String tenantId, int count);

    void eventsProcessed(String tenantId, int count);

    void eventsFailed(String tenantId, String reason, int count);

    void duplicateEvents(String tenantId, String stage, int count);

    void providerDeliverySuccess(String destinationId, int count);

    void providerDeliveryFailure(String destinationId, String reason, int count);

    /** A delivery was parked for retry {@code attempt}. Rising retries are the first sign of a provider outage. */
    default void retryScheduled(String destinationId, int attempt) {
    }

    /** A message was moved to the dead-letter queue: it needs a human. */
    default void deadLettered(String destinationId, String reason) {
    }

    /** Time from the Event API receiving an event to the provider accepting it. */
    default void deliveryLatency(String destinationId, java.time.Duration latency) {
    }

    static EventMetrics noop() {
        return new EventMetrics() {
            @Override public void eventsReceived(String tenantId, int count) {}
            @Override public void eventsValidated(String tenantId, int count) {}
            @Override public void eventsRejected(String tenantId, String reason, int count) {}
            @Override public void eventsQueued(String tenantId, int count) {}
            @Override public void eventsProcessed(String tenantId, int count) {}
            @Override public void eventsFailed(String tenantId, String reason, int count) {}
            @Override public void duplicateEvents(String tenantId, String stage, int count) {}
            @Override public void providerDeliverySuccess(String destinationId, int count) {}
            @Override public void providerDeliveryFailure(String destinationId, String reason, int count) {}
        };
    }
}
