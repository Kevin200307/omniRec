// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.compat.V1Compat;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Builds canonical (envelope v2) events on the merchant's backend and hands
 * them to an {@link EventSender}.
 *
 * <h2>Identity on the server</h2>
 *
 * A server-side event should carry the browser's {@code anonymousId} and
 * {@code sessionId} whenever there is a browser request behind it: they are
 * what link the purchase to the browsing that led to it. The Spring starter
 * reads them from the {@code omnirec_anonymous_id} and {@code omnirec_session_id}
 * cookies automatically.
 *
 * With neither, the user id is required, and a deterministic
 * {@code server:<uuid-of-userId>} anonymousId is derived. It is stable for that
 * user, so their server-side events stay coherent with one another.
 *
 * <h2>Idempotency</h2>
 *
 * Events with a natural business key derive their eventId from it (see
 * {@link #deterministicEventId}). A purchase reported twice — by a retry, or by
 * the frontend as well as the backend — produces the same eventId both times
 * and is deduplicated by the pipeline instead of double-counted.
 */
public class ServerEventEmitter {

    private final EventSender sender;
    private final EventValidator validator;
    private final String tenantId;
    private final boolean validateEvents;

    public ServerEventEmitter(EventSender sender, EventValidator validator, String tenantId, boolean validateEvents) {
        this.sender = sender;
        this.validator = validator;
        this.tenantId = tenantId;
        this.validateEvents = validateEvents;
    }

    /**
     * Tracks an event. Returns its id.
     *
     * @param event       a catalog name ({@code StandardEvents.ORDER_PLACED}) or a plan event
     * @param data        the event's {@code data} blocks, for example {@code Map.of("order", Map.of("id", "o1"))}
     * @param identity    who the event belongs to
     * @param properties  free-form attributes outside {@code data}
     * @param businessKey when non-null, the eventId is derived from it so repeated reports collapse
     * @throws IllegalArgumentException when the event fails validation: a backend
     *                                  event is authoritative business data, so it fails loudly at the call site
     */
    public String track(String event, EventData data, ServerIdentity identity, Map<String, Object> properties,
                        String businessKey) {
        EventName name = EventName.of(event);
        CommerceEvent built = CommerceEvent.builder()
                .eventId(businessKey == null ? UUID.randomUUID().toString() : deterministicEventId(name, businessKey))
                .eventType(name)
                .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                .source(EventSource.SERVER)
                .timestamp(Instant.now())
                .tenantId(tenantId)
                .identity(resolveIdentity(identity))
                .context(EventContext.server())
                .data(data == null ? EventData.empty() : data)
                .properties(properties == null ? Map.of() : properties)
                .build();

        if (validateEvents) {
            ValidationResult result = validator.validate(built);
            if (!result.valid()) {
                throw new IllegalArgumentException("Invalid " + event + " event: " + result.describe());
            }
        }
        sender.send(built);
        return built.eventId();
    }

    public String track(String event, Map<String, ?> data, ServerIdentity identity) {
        return track(event, EventData.of(data), identity, Map.of(), null);
    }

    /** The v1 path used by the per-event trackers: converts the flat payload to v2 blocks. */
    public void emit(EventName eventType, ServerIdentity identity, CommerceData commerce, Map<String, Object> properties) {
        emit(eventType, identity, commerce, properties, null);
    }

    /**
     * @param businessKey when non-null, the eventId is derived from it so
     *                    repeated reports of the same business fact collapse
     */
    public void emit(
            EventName eventType,
            ServerIdentity identity,
            CommerceData commerce,
            Map<String, Object> properties,
            String businessKey
    ) {
        track(eventType.wireName(), V1Compat.toData(commerce), identity, properties, businessKey);
    }

    public void identify(String anonymousId, String userId) {
        if (anonymousId == null || anonymousId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("identify requires both an anonymousId and a userId");
        }
        track(StandardEventNames.IDENTIFY.wireName(), EventData.empty(),
                new ServerIdentity(anonymousId, userId, null), Map.of(), anonymousId + ":" + userId);
    }

    public void flush() {
        sender.flush();
    }

    private EventIdentity resolveIdentity(ServerIdentity identity) {
        boolean hasUser = identity != null && !isBlank(identity.userId());
        boolean hasAnonymous = identity != null && !isBlank(identity.anonymousId());
        if (!hasUser && !hasAnonymous) {
            throw new IllegalArgumentException(
                    "Server-side events need the visitor's anonymousId (from the omnirec_anonymous_id cookie) or a userId");
        }

        String anonymousId = hasAnonymous ? identity.anonymousId() : derivedAnonymousId(identity.userId());
        String sessionId = !isBlank(identity.sessionId())
                ? identity.sessionId()
                // A backend action genuinely has no browsing session. A stable
                // per-visitor server session keeps these events grouped together
                // rather than scattering each one into a session of its own.
                : "server:" + derivedUuid("session:" + (hasUser ? identity.userId() : anonymousId));

        return hasUser
                ? EventIdentity.authenticated(anonymousId, identity.userId(), sessionId)
                : EventIdentity.anonymous(anonymousId, sessionId);
    }

    private String derivedAnonymousId(String userId) {
        return "server:" + derivedUuid("anon:" + userId);
    }

    /**
     * {@code evt:<eventType>:<businessKey>}, e.g. {@code evt:purchase_completed:order_1}.
     *
     * Deterministic across processes and restarts, so two nodes reporting the
     * same order agree on the id. And byte-identical to what the frontend SDK
     * derives ({@code businessEventId} in @omnirec/commerce-web), so a purchase
     * reported from both the browser and the server deduplicates too.
     */
    public static String deterministicEventId(EventName eventType, String businessKey) {
        return "evt:" + eventType.wireName() + ":" + businessKey;
    }

    private static String derivedUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Identity as a backend caller knows it. An anonymousId or a userId is required. */
    public record ServerIdentity(String anonymousId, String userId, String sessionId) {

        public static ServerIdentity ofUser(String userId) {
            return new ServerIdentity(null, userId, null);
        }

        public static ServerIdentity of(String anonymousId, String userId) {
            return new ServerIdentity(anonymousId, userId, null);
        }

        /** The same visitor, now known to be {@code userId}. */
        public ServerIdentity withUser(String userId) {
            return new ServerIdentity(anonymousId, userId, sessionId);
        }
    }
}
