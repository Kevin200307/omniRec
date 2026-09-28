// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

import java.time.Duration;

/**
 * The storage worker's meters, following the conventions of
 * MicrometerEventMetrics: tags are bounded ({@code tenant} comes from
 * configuration, {@code reason} from a fixed vocabulary), never per event.
 *
 * <pre>
 *   omnirec.storage.events.received    {tenant}
 *   omnirec.storage.events.persisted   {tenant}
 *   omnirec.storage.events.duplicates  {tenant}          redeliveries absorbed by the unique key
 *   omnirec.storage.events.failed      {tenant, reason}  transient | permanent | unexpected
 *   omnirec.storage.write.duration                        time spent in EventStore.save
 *   omnirec.storage.lag                                   Event API receipt -> row committed
 *   omnirec.storage.history.duration                      customer history queries
 * </pre>
 *
 * Delivery-level meters (retries, dead-lettering, queue depth) come from the
 * pipeline itself, tagged {@code destination=event-storage}.
 */
public class StorageMetrics {

    private final MeterRegistry registry;

    public StorageMetrics(MeterRegistry registry) {
        // An empty composite registry records nothing: the no-registry fallback.
        this.registry = registry == null ? new CompositeMeterRegistry() : registry;
    }

    public void received(String tenantId) {
        registry.counter("omnirec.storage.events.received", "tenant", tenant(tenantId)).increment();
    }

    public void persisted(String tenantId) {
        registry.counter("omnirec.storage.events.persisted", "tenant", tenant(tenantId)).increment();
    }

    public void duplicate(String tenantId) {
        registry.counter("omnirec.storage.events.duplicates", "tenant", tenant(tenantId)).increment();
    }

    public void failed(String tenantId, String reason) {
        registry.counter("omnirec.storage.events.failed", "tenant", tenant(tenantId), "reason", reason).increment();
    }

    public void writeDuration(Duration duration) {
        registry.timer("omnirec.storage.write.duration").record(duration);
    }

    public void lag(Duration lag) {
        if (!lag.isNegative()) {
            registry.timer("omnirec.storage.lag").record(lag);
        }
    }

    public void historyQuery(Duration duration) {
        registry.timer("omnirec.storage.history.duration").record(duration);
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "unknown" : tenantId;
    }
}
