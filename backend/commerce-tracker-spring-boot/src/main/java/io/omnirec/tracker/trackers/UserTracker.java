package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.EventType;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Map;

/**
 * Account lifecycle events. The backend is the right source: registration
 * succeeds or fails on the server, and a profile update is only real once it
 * has been persisted.
 *
 * Passing the {@code anonymousId} on registration is worth doing — it is what
 * connects everything the shopper browsed before signing up to the account they
 * just created, which is exactly the history a cold-start recommender needs.
 */
public class UserTracker {

    private final ServerEventEmitter emitter;

    public UserTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    public void registered(String userId, String anonymousId) {
        registered(userId, anonymousId, Map.of());
    }

    public void registered(String userId, String anonymousId, Map<String, Object> traits) {
        emitter.emit(
                EventType.USER_REGISTERED,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.empty(),
                traits,
                // One registration per user, ever — so the userId is a perfect
                // idempotency key against a retried signup request.
                userId);
    }

    public void loggedIn(String userId, String anonymousId) {
        emitter.emit(
                EventType.USER_LOGGED_IN,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.empty(),
                Map.of());
    }

    public void profileUpdated(String userId, Map<String, Object> changedFields) {
        emitter.emit(
                EventType.USER_PROFILE_UPDATED,
                ServerIdentity.ofUser(userId),
                CommerceData.empty(),
                changedFields == null ? Map.of() : changedFields);
    }
}
