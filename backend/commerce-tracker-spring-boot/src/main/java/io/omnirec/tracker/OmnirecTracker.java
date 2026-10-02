// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.model.EventData;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;
import io.omnirec.tracker.web.OmnirecRequestIdentity;

import java.util.Map;

/**
 * The backend tracking API. Inject it and track in one line; the visitor's
 * browsing identity comes from the request automatically:
 *
 * <pre>{@code
 * tracker.track(StandardEvents.PRODUCT_ADDED_TO_CART,
 *         Map.of("product", Map.of("id", productId, "quantity", quantity)));
 *
 * tracker.track(StandardEvents.PURCHASE_COMPLETED, orderData, customerId, "order:" + order.getId());
 * }</pre>
 *
 * Inside a database transaction with the outbox enabled, the event is written
 * with the transaction and sent only after it commits.
 */
public class OmnirecTracker {

    private final ServerEventEmitter emitter;

    public OmnirecTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    /** For the visitor behind the current request. */
    public String track(String event, Map<String, ?> data) {
        return emitter.track(event, EventData.of(data), requestIdentity(null), Map.of(), null);
    }

    /** For the visitor behind the current request, now known to be {@code userId}. */
    public String track(String event, Map<String, ?> data, String userId) {
        return emitter.track(event, EventData.of(data), requestIdentity(userId), Map.of(), null);
    }

    /**
     * @param businessKey derives a stable eventId from a business fact (an order
     *                    id), so a retried or doubly-reported fact deduplicates
     */
    public String track(String event, Map<String, ?> data, String userId, String businessKey) {
        return emitter.track(event, EventData.of(data), requestIdentity(userId), Map.of(), businessKey);
    }

    /** With an explicit identity, for background jobs, webhooks and other non-request code. */
    public String track(String event, Map<String, ?> data, ServerIdentity identity, Map<String, Object> properties,
                        String businessKey) {
        return emitter.track(event, EventData.of(data), identity, properties, businessKey);
    }

    /** Links the visitor behind the current request to {@code userId}. */
    public void identify(String userId) {
        ServerIdentity current = OmnirecRequestIdentity.current()
                .orElseThrow(() -> new IllegalStateException(
                        "identify(userId) needs a request with the omnirec_anonymous_id cookie; "
                                + "use identify(anonymousId, userId) elsewhere"));
        emitter.identify(current.anonymousId(), userId);
    }

    public void identify(String anonymousId, String userId) {
        emitter.identify(anonymousId, userId);
    }

    public void flush() {
        emitter.flush();
    }

    private static ServerIdentity requestIdentity(String userId) {
        ServerIdentity base = OmnirecRequestIdentity.current().orElse(new ServerIdentity(null, null, null));
        return userId == null ? base : base.withUser(userId);
    }
}
