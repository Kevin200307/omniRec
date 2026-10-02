// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.omnirec.commerce.compat.V1Compat;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical, provider-independent commerce event (envelope v2) — the one
 * shape every producer is converted to and every destination adapter consumes.
 *
 * Immutable by construction: a record with defensively copied maps. Events fan
 * out to several destinations concurrently, and a mapper that could mutate a
 * shared event would corrupt whatever the next destination sees.
 *
 * Nothing here may reference Amazon, Google, or Azure. If a field only makes
 * sense for one provider, it belongs in that provider's mapper.
 *
 * On the wire the name is {@code event}; the Java accessor keeps the name
 * {@code eventType()} so call sites read naturally ({@code event.eventType()}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommerceEvent(
        String eventId,
        @JsonProperty("event") EventName eventType,
        int eventVersion,
        String kind,
        String schemaVersion,
        EventSource source,
        Instant timestamp,
        String tenantId,
        EventIdentity identity,
        EventContext context,
        EventData data,
        Map<String, Object> properties,
        /** Set by the validator in permissive mode for events in neither the catalog nor the plan. Null otherwise. */
        Boolean unplanned,
        /** Set by the Event API on receipt. Distinct from {@code timestamp}, which the client stamps. */
        Instant receivedAt
) {

    public static final String CURRENT_SCHEMA_VERSION = "2.0";
    public static final String KIND_STANDARD = "standard";
    public static final String KIND_CUSTOM = "custom";

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
        eventVersion = eventVersion < 1 ? 1 : eventVersion;
        kind = kind == null ? KIND_STANDARD : kind;
        schemaVersion = schemaVersion == null ? CURRENT_SCHEMA_VERSION : schemaVersion;
        identity = identity == null ? EventIdentity.anonymous(null, null) : identity;
        context = context == null ? EventContext.empty() : context;
        data = data == null ? EventData.empty() : data;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        unplanned = Boolean.TRUE.equals(unplanned) ? Boolean.TRUE : null;
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

    @JsonIgnore
    public boolean isUnplanned() {
        return Boolean.TRUE.equals(unplanned);
    }

    @JsonIgnore
    public boolean isCustom() {
        return KIND_CUSTOM.equals(kind);
    }

    /**
     * The payload in the v1 flat shape. For v1 consumers only (the customer
     * history response and old integrations); new code reads {@link #data()}.
     *
     * @deprecated read the v2 blocks from {@link #data()}
     */
    @Deprecated
    @JsonIgnore
    public CommerceData commerce() {
        return V1Compat.toCommerce(data);
    }

    public CommerceEvent withIdentity(EventIdentity newIdentity) {
        return toBuilder().identity(newIdentity).build();
    }

    public CommerceEvent withContext(EventContext newContext) {
        return toBuilder().context(newContext).build();
    }

    public CommerceEvent withTenantId(String newTenantId) {
        return toBuilder().tenantId(newTenantId).build();
    }

    public CommerceEvent withReceivedAt(Instant instant) {
        return toBuilder().receivedAt(instant).build();
    }

    public CommerceEvent withTimestamp(Instant instant) {
        return toBuilder().timestamp(instant).build();
    }

    public CommerceEvent withData(EventData newData) {
        return toBuilder().data(newData).build();
    }

    public Builder toBuilder() {
        return new Builder()
                .eventId(eventId).eventType(eventType).eventVersion(eventVersion).kind(kind)
                .schemaVersion(schemaVersion).source(source).timestamp(timestamp).tenantId(tenantId)
                .identity(identity).context(context).data(data).properties(properties)
                .unplanned(unplanned).receivedAt(receivedAt);
    }

    public static final class Builder {
        private String eventId;
        private EventName eventType;
        private int eventVersion = 1;
        private String kind = KIND_STANDARD;
        private String schemaVersion = CURRENT_SCHEMA_VERSION;
        private EventSource source;
        private Instant timestamp;
        private String tenantId;
        private EventIdentity identity;
        private EventContext context;
        private EventData data;
        private CommerceData commerce;
        private Map<String, Object> properties = Map.of();
        private Boolean unplanned;
        private Instant receivedAt;

        public Builder eventId(String eventId) { this.eventId = eventId; return this; }
        public Builder eventType(EventName eventType) { this.eventType = eventType; return this; }
        public Builder eventType(String eventType) { this.eventType = EventName.of(eventType); return this; }
        public Builder eventVersion(int eventVersion) { this.eventVersion = eventVersion; return this; }
        public Builder kind(String kind) { this.kind = kind; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder source(EventSource source) { this.source = source; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder identity(EventIdentity identity) { this.identity = identity; return this; }
        public Builder context(EventContext context) { this.context = context; return this; }
        public Builder data(EventData data) { this.data = data; this.commerce = null; return this; }
        public Builder properties(Map<String, Object> properties) { this.properties = properties; return this; }
        public Builder unplanned(Boolean unplanned) { this.unplanned = unplanned; return this; }
        public Builder receivedAt(Instant receivedAt) { this.receivedAt = receivedAt; return this; }

        /**
         * Sets the payload from the v1 flat shape, converted with {@link V1Compat}.
         *
         * @deprecated build v2 blocks with {@link #data(EventData)}
         */
        @Deprecated
        public Builder commerce(CommerceData commerce) { this.commerce = commerce; this.data = null; return this; }

        public CommerceEvent build() {
            EventData payload = data != null ? data : commerce != null ? V1Compat.toData(commerce) : EventData.empty();
            return new CommerceEvent(eventId, eventType, eventVersion, kind, schemaVersion, source,
                    timestamp == null ? Instant.now() : timestamp,
                    tenantId, identity, context, payload, properties, unplanned, receivedAt);
        }
    }
}
