// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.normalize;

import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.compat.V1Compat;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.DeviceType;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.commerce.model.Platform;
import io.omnirec.eventapi.dto.EventDto;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a client-supplied {@link EventDto} into a canonical {@link CommerceEvent},
 * establishing server-owned fields and refusing to trust client-owned ones.
 *
 * Specifically:
 * <ul>
 *   <li>{@code tenantId} comes from the authenticated API key, never the body;</li>
 *   <li>{@code ip} and {@code country} are derived from the request — a value
 *       in the payload is discarded, since anyone can put anything there;</li>
 *   <li>{@code url} and {@code referrer} are scrubbed of tokens, emails and
 *       credentials (see {@link UrlSanitizer});</li>
 *   <li>{@code receivedAt} is stamped here, so a clock-skewed or spoofed client
 *       {@code timestamp} can still be reasoned about;</li>
 *   <li>an absurd {@code timestamp} is clamped rather than rejected — a device
 *       with a wrong clock is a real and common thing, and dropping its events
 *       loses more than accepting them with a corrected time;</li>
 *   <li>a v1 event ({@code eventType} + {@code commerce}) is converted to
 *       envelope v2 with {@link V1Compat}, so everything downstream sees v2;</li>
 *   <li>an alias is rewritten to its event's canonical name, and
 *       {@code kind} and {@code eventVersion} come from the registry.</li>
 * </ul>
 */
public class EventNormalizer {

    /** Beyond this in either direction, the client clock is wrong, not the event. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofDays(2);

    private final boolean retainIpAddress;
    private final EventRegistry registry;

    public EventNormalizer(boolean retainIpAddress) {
        this(retainIpAddress, EventRegistry.standard());
    }

    public EventNormalizer(boolean retainIpAddress, EventRegistry registry) {
        this.retainIpAddress = retainIpAddress;
        this.registry = registry;
    }

    /** Normalizes against the default registry. */
    public CommerceEvent normalize(EventDto dto, String tenantId, RequestMetadata request) {
        return normalize(dto, tenantId, request, registry);
    }

    /**
     * Normalizes against a specific registry, for example a tenant's catalog
     * plus tracking plan.
     *
     * @throws IllegalArgumentException when the event has no name
     */
    public CommerceEvent normalize(EventDto dto, String tenantId, RequestMetadata request, EventRegistry tenantRegistry) {
        Instant receivedAt = request.receivedAt();
        EventContext context = enrich(dto.context(), request);

        EventName sent = dto.name();
        if (sent == null) throw new IllegalArgumentException("event name is missing");
        EventDefinition definition = tenantRegistry.find(sent.wireName()).orElse(null);
        EventName name = definition == null ? sent : EventName.of(definition.name());
        EventData data = dto.data() != null && !dto.data().isEmpty()
                ? dto.data()
                : V1Compat.toData(dto.commerce());

        return CommerceEvent.builder()
                // A missing eventId would defeat deduplication, so we mint one
                // rather than reject: an event with a server-side id is still
                // deduplicable against redelivery of this same request.
                .eventId(dto.eventId() == null || dto.eventId().isBlank()
                        ? UUID.randomUUID().toString()
                        : dto.eventId())
                .eventType(name)
                .eventVersion(dto.eventVersion() != null ? dto.eventVersion()
                        : definition != null ? definition.version() : 1)
                .kind(definition != null ? definition.kind() : CommerceEvent.KIND_CUSTOM)
                .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                .source(dto.source() != null ? dto.source() : inferSource(context))
                .timestamp(clampTimestamp(dto.timestamp(), receivedAt))
                .tenantId(tenantId)
                .identity(dto.identity())
                .context(context)
                .data(data)
                .properties(dto.properties())
                .receivedAt(receivedAt)
                .build();
    }

    /** v1 clients do not say where they run; the platform tells us. */
    private static EventSource inferSource(EventContext context) {
        return context.platform() == Platform.SERVER ? EventSource.SERVER : EventSource.BROWSER;
    }

    private EventContext enrich(EventContext clientContext, RequestMetadata request) {
        EventContext base = clientContext == null ? EventContext.empty() : clientContext;
        DeviceType device = DeviceType.fromUserAgent(request.userAgent());

        // Scrubbed here as well as in the SDK: anyone can POST directly.
        EventContext scrubbed = base.withUrls(UrlSanitizer.sanitize(base.url()), UrlSanitizer.sanitize(base.referrer()));
        EventContext enriched = scrubbed.enrichedWith(request.ip(), request.country(), device);
        return retainIpAddress ? enriched : enriched.withoutIp();
    }

    /**
     * Keeps the client timestamp when it's plausible, otherwise substitutes the
     * receive time. Ordering downstream depends on timestamps, and one device
     * stuck in 1970 would otherwise sort itself to the beginning of every
     * visitor's history.
     */
    private Instant clampTimestamp(Instant clientTimestamp, Instant receivedAt) {
        if (clientTimestamp == null) return receivedAt;
        if (clientTimestamp.isAfter(receivedAt.plus(MAX_CLOCK_SKEW))) return receivedAt;
        if (clientTimestamp.isBefore(receivedAt.minus(MAX_CLOCK_SKEW))) return receivedAt;
        return clientTimestamp;
    }

    /** Everything the normalizer needs from the HTTP request, decoupled from the servlet API for testing. */
    public record RequestMetadata(String ip, String country, String userAgent, Instant receivedAt) {

        public static RequestMetadata of(String ip, String country, String userAgent) {
            return new RequestMetadata(ip, country, userAgent, Instant.now());
        }
    }
}
