// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical, provider-independent commerce event — the one shape every
 * tracker produces and every destination adapter consumes.
 *
 * Immutable by construction: a record with defensively copied maps. Events fan
 * out to several destinations concurrently, and a mapper that could mutate a
 * shared event would corrupt whatever the next destination sees.
 *
 * Nothing here may reference Amazon, Google, or Azure. If a field only makes
 * sense for one provider, it belongs in that provider's mapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommerceEvent(
        String eventId,
        EventType eventType,
        String schemaVersion,
        Instant timestamp,
        String tenantId,
        EventIdentity identity,
        EventContext context,
        CommerceData commerce,
        Map<String, Object> properties,
        /** Set by the Event API on receipt. Distinct from {@code timestamp}, which the client stamps. */
        Instant receivedAt
) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    /** Foreground time on a product, attached to an engagement update. */
    public static final String PROPERTY_DWELL_TIME_MS = "dwellTimeMs";

    /**
     * Set on an engagement update: the eventId of the view it measures. Its
     * presence is what distinguishes "the same view, now with a duration" from
     * a second, separate view.
     */
    public static final String PROPERTY_VIEW_EVENT_ID = "viewEventId";

    public CommerceEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        schemaVersion = schemaVersion == null ? CURRENT_SCHEMA_VERSION : schemaVersion;
        identity = identity == null ? EventIdentity.anonymous(null, null) : identity;
        context = context == null ? EventContext.empty() : context;
        commerce = commerce == null ? CommerceData.empty() : commerce;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The identity a provider should attribute this event to: the user if known, else the anonymous visitor. */
    @JsonIgnore
    public String resolvedActorId() {
        return identity.isAuthenticated() ? identity.userId() : identity.anonymousId();
    }

    /**
     * True for a follow-up that adds measurements to an earlier event (dwell
     * time on a {@code product_viewed}) rather than recording a new interaction.
     *
     * Interaction-counting destinations must skip these: forwarding one as an
     * interaction would count every product view twice.
     */
    @JsonIgnore
    public boolean isEngagementUpdate() {
        return properties.containsKey(PROPERTY_VIEW_EVENT_ID);
    }

    public CommerceEvent withIdentity(EventIdentity newIdentity) {
        return new CommerceEvent(eventId, eventType, schemaVersion, timestamp, tenantId,
                newIdentity, context, commerce, properties, receivedAt);
    }

    public CommerceEvent withContext(EventContext newContext) {
        return new CommerceEvent(eventId, eventType, schemaVersion, timestamp, tenantId,
                identity, newContext, commerce, properties, receivedAt);
    }

    public CommerceEvent withTenantId(String newTenantId) {
        return new CommerceEvent(eventId, eventType, schemaVersion, timestamp, newTenantId,
                identity, context, commerce, properties, receivedAt);
    }

    public CommerceEvent withReceivedAt(Instant instant) {
        return new CommerceEvent(eventId, eventType, schemaVersion, timestamp, tenantId,
                identity, context, commerce, properties, instant);
    }

    public CommerceEvent withTimestamp(Instant instant) {
        return new CommerceEvent(eventId, eventType, schemaVersion, instant, tenantId,
                identity, context, commerce, properties, receivedAt);
    }

    public static final class Builder {
        private String eventId;
        private EventType eventType;
        private String schemaVersion = CURRENT_SCHEMA_VERSION;
        private Instant timestamp;
        private String tenantId;
        private EventIdentity identity;
        private EventContext context;
        private CommerceData commerce;
        private Map<String, Object> properties = Map.of();
        private Instant receivedAt;

        public Builder eventId(String eventId) { this.eventId = eventId; return this; }
        public Builder eventType(EventType eventType) { this.eventType = eventType; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder identity(EventIdentity identity) { this.identity = identity; return this; }
        public Builder context(EventContext context) { this.context = context; return this; }
        public Builder commerce(CommerceData commerce) { this.commerce = commerce; return this; }
        public Builder properties(Map<String, Object> properties) { this.properties = properties; return this; }
        public Builder receivedAt(Instant receivedAt) { this.receivedAt = receivedAt; return this; }

        public CommerceEvent build() {
            return new CommerceEvent(eventId, eventType, schemaVersion,
                    timestamp == null ? Instant.now() : timestamp,
                    tenantId, identity, context, commerce, properties, receivedAt);
        }
    }
}
