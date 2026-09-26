// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;

import java.time.Instant;
import java.util.Map;

/**
 * The wire shape accepted by the Event API. Mirrors {@code CommerceEvent} in
 * {@code packages/commerce-web/src/events/types.ts}.
 *
 * Deliberately a separate type from {@link io.omnirec.commerce.model.CommerceEvent}
 * rather than deserialising straight into it. The canonical model has
 * server-owned fields — {@code receivedAt}, the resolved {@code tenantId}, the
 * request-derived context — and binding client JSON directly onto it would let
 * a caller set them.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is what makes rolling
 * upgrades survivable: a newer SDK sending a field this server doesn't know
 * about must not 400 the whole batch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDto(
        String eventId,
        EventType eventType,
        String schemaVersion,
        Instant timestamp,
        EventIdentity identity,
        EventContext context,
        CommerceData commerce,
        Map<String, Object> properties
) {
}
