// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventSource;

import java.time.Instant;
import java.util.Map;

/** Builds the envelope of a derived event. */
public final class DerivedEvents {

    private DerivedEvents() {
    }

    /**
     * @param name      catalog event name
     * @param tenantId  tenant of the events it was derived from
     * @param cause     what makes this occurrence unique (see {@link RuleContext#derivedEventId})
     * @param identity  the customer it is about
     * @param timestamp when it happened
     */
    public static CommerceEvent create(String name, String tenantId, String cause, EventIdentity identity,
                                       Instant timestamp, Instant now, Map<String, ?> data,
                                       Map<String, Object> properties) {
        int version = EventRegistry.standard().find(name).map(d -> d.version()).orElse(1);
        return CommerceEvent.builder()
                .eventId(RuleContext.derivedEventId(name, tenantId, cause))
                .eventType(name)
                .eventVersion(version)
                .kind(CommerceEvent.KIND_STANDARD)
                .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                .source(EventSource.DERIVED)
                .timestamp(timestamp)
                .receivedAt(now)
                .tenantId(tenantId)
                .identity(identity)
                .context(EventContext.server())
                .data(EventData.of(data))
                .properties(properties)
                .build();
    }

    /** Identity carried in a timer payload, as written by {@link #identityPayload}. */
    public static EventIdentity identity(Timer timer) {
        return new EventIdentity(timer.get("anonymousId"), timer.get("userId"), timer.get("sessionId"));
    }

    /** The parts of an event a timer needs to describe the same customer later. */
    public static Map<String, String> identityPayload(CommerceEvent event) {
        Map<String, String> payload = new java.util.HashMap<>();
        payload.put("tenantId", event.tenantId());
        payload.put("anonymousId", event.identity().anonymousId());
        payload.put("sessionId", event.identity().sessionId());
        if (event.identity().userId() != null) payload.put("userId", event.identity().userId());
        return payload;
    }
}
