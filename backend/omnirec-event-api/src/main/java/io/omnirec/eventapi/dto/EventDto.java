// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.EventSource;

import java.time.Instant;
import java.util.Map;

/**
 * The wire shape accepted by the Event API: envelope v2, with the v1 fields
 * still accepted so existing SDKs keep working.
 *
 * <ul>
 *   <li>v2 sends {@code event} and {@code data};</li>
 *   <li>v1 sends {@code eventType} and the flat {@code commerce} object.
 *       {@link io.omnirec.eventapi.normalize.EventNormalizer} converts it to v2
 *       with {@link io.omnirec.commerce.compat.V1Compat} before validation.</li>
 * </ul>
 *
 * Deliberately a separate type from {@link io.omnirec.commerce.model.CommerceEvent}
 * rather than deserialising straight into it. The canonical model has
 * server-owned fields — {@code receivedAt}, the resolved {@code tenantId},
 * {@code kind}, {@code unplanned}, the request-derived context — and binding
 * client JSON directly onto it would let a caller set them.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is what makes rolling
 * upgrades survivable: a newer SDK sending a field this server doesn't know
 * about must not 400 the whole batch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDto(
        String eventId,
        EventName event,
        /** v1 name field. Ignored when {@code event} is present. */
        EventName eventType,
        Integer eventVersion,
        String schemaVersion,
        EventSource source,
        Instant timestamp,
        EventIdentity identity,
        EventContext context,
        EventData data,
        /** v1 payload. Ignored when {@code data} is present. */
        CommerceData commerce,
        Map<String, Object> properties
) {

    /** A v1-shaped event, as the v1 SDKs send it. */
    public EventDto(String eventId, EventName eventType, String schemaVersion, Instant timestamp,
                    EventIdentity identity, EventContext context, CommerceData commerce,
                    Map<String, Object> properties) {
        this(eventId, null, eventType, null, schemaVersion, null, timestamp, identity, context, null, commerce,
                properties);
    }

    /** A v2-shaped event. */
    public static EventDto v2(String eventId, EventName event, EventSource source, Instant timestamp,
                              EventIdentity identity, EventContext context, EventData data,
                              Map<String, Object> properties) {
        return new EventDto(eventId, event, null, null, "2.0", source, timestamp, identity, context, data, null,
                properties);
    }

    /** The name the client sent, from whichever version of the envelope it used. */
    public EventName name() {
        return event != null ? event : eventType;
    }
}
