// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Builds canonical events from the backend trackers and hands them to an
 * {@link EventSender}.
 *
 * <h2>Identity on the server</h2>
 *
 * A server-side event usually knows the {@code userId} but not the browser's
 * {@code anonymousId} or {@code sessionId}, and the canonical schema requires
 * the first two. The merchant should pass the anonymousId when they have it —
 * captured at checkout and stored against the order is the usual way, and it is
 * what links the purchase back to the browsing that led to it.
 *
 * When they don't, we derive a deterministic {@code server:<uuid-of-userId>}
 * anonymousId. It is stable for that user, so their server-side events stay
 * coherent with one another, and the identity link ties it to the same customer
 * as their browser identity. It does <em>not</em> magically join the two — that
 * is why passing the real anonymousId is worth the trouble.
 *
 * <h2>Idempotency</h2>
 *
 * Events with a natural business key derive their eventId from it (see
 * {@link #deterministicEventId}). A purchase reported twice — by a retry, or by
 * the frontend as well as the backend — produces the same eventId both times
 * and is deduplicated by the pipeline instead of double-counted.
 */
public class ServerEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(ServerEventEmitter.class);

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

    public void emit(EventType eventType, ServerIdentity identity, CommerceData commerce, Map<String, Object> properties) {
        emit(eventType, identity, commerce, properties, null);
    }

    /**
     * @param businessKey when non-null, the eventId is derived from it so
     *                    repeated reports of the same business fact collapse
     */
    public void emit(
            EventType eventType,
            ServerIdentity identity,
            CommerceData commerce,
            Map<String, Object> properties,
            String businessKey
    ) {
        CommerceEvent event = CommerceEvent.builder()
                .eventId(businessKey == null
                        ? UUID.randomUUID().toString()
                        : deterministicEventId(eventType, businessKey))
                .eventType(eventType)
                .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                .timestamp(Instant.now())
                .tenantId(tenantId)
                .identity(resolveIdentity(identity))
                .context(EventContext.server())
                .commerce(commerce == null ? CommerceData.empty() : commerce)
                .properties(properties == null ? Map.of() : properties)
                .build();

        if (validateEvents) {
            ValidationResult result = validator.validate(event);
            if (!result.valid()) {
                // Throw rather than drop: a backend event is authoritative
                // business data, and silently discarding a purchase because a
                // field was missing is far worse than failing loudly at the
                // call site while the developer is looking at it.
                throw new IllegalArgumentException(
                        "Invalid " + eventType.wireName() + " event: " + result.describe());
            }
        }

        sender.send(event);
    }

    public void identify(String anonymousId, String userId) {
        if (anonymousId == null || anonymousId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("identify requires both an anonymousId and a userId");
        }
        emit(EventType.IDENTIFY,
                new ServerIdentity(anonymousId, userId, null),
                CommerceData.empty(),
                Map.of(),
                anonymousId + ":" + userId);
    }

    public void flush() {
        sender.flush();
    }

    private EventIdentity resolveIdentity(ServerIdentity identity) {
        if (identity == null || identity.userId() == null || identity.userId().isBlank()) {
            throw new IllegalArgumentException("Server-side events require a userId");
        }

        String anonymousId = identity.anonymousId() != null && !identity.anonymousId().isBlank()
                ? identity.anonymousId()
                : derivedAnonymousId(identity.userId());

        String sessionId = identity.sessionId() != null && !identity.sessionId().isBlank()
                ? identity.sessionId()
                // A backend action genuinely has no browsing session. A stable
                // per-user server session keeps these events grouped together
                // rather than scattering each one into a session of its own.
                : "server:" + derivedUuid("session:" + identity.userId());

        return EventIdentity.authenticated(anonymousId, identity.userId(), sessionId);
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
     * reported from both the browser and the server deduplicates too. A
     * readable string rather than a hash keeps the two implementations trivially
     * in step: there is no hashing code to keep identical across languages.
     */
    public static String deterministicEventId(EventType eventType, String businessKey) {
        return "evt:" + eventType.wireName() + ":" + businessKey;
    }

    private static String derivedUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Identity as a backend caller knows it. Only {@code userId} is required. */
    public record ServerIdentity(String anonymousId, String userId, String sessionId) {

        public static ServerIdentity ofUser(String userId) {
            return new ServerIdentity(null, userId, null);
        }

        public static ServerIdentity of(String anonymousId, String userId) {
            return new ServerIdentity(anonymousId, userId, null);
        }
    }
}
