package io.omnirec.tracker;

import io.omnirec.tracker.trackers.CartTracker;
import io.omnirec.tracker.trackers.CheckoutTracker;
import io.omnirec.tracker.trackers.ProductTracker;
import io.omnirec.tracker.trackers.PurchaseTracker;
import io.omnirec.tracker.trackers.RecommendationTracker;
import io.omnirec.tracker.trackers.UserTracker;

/**
 * The facade a merchant's Spring Boot application injects.
 *
 * <pre>
 *   &#64;Service
 *   class OrderService {
 *       private final CommerceTracker commerce;
 *
 *       void onPaymentSettled(Order order) {
 *           commerce.purchase.completed(PurchaseCompleted.builder()
 *                   .orderId(order.getId())
 *                   .userId(order.getCustomerId())
 *                   .anonymousId(order.getTrackingAnonymousId())
 *                   .items(...)
 *                   .total(order.getTotal())
 *                   .currency("USD")
 *                   .build());
 *       }
 *   }
 * </pre>
 *
 * <h2>What belongs here rather than in the browser</h2>
 *
 * Anything where the browser cannot be trusted to know the truth: whether a
 * payment settled, whether a refund went through, whether a review survived
 * moderation. A confirmation page can be reloaded, bookmarked, closed before it
 * renders, or blocked outright — so a purchase reported only from JavaScript is
 * both over- and under-counted. The backend knows.
 *
 * The sub-trackers are exposed as final fields so the call reads the way the
 * documentation describes it: {@code commerce.purchase.completed(...)}.
 */
public class CommerceTracker {

    public final ProductTracker product;
    public final CartTracker cart;
    public final CheckoutTracker checkout;
    public final PurchaseTracker purchase;
    public final RecommendationTracker recommendation;
    public final UserTracker user;

    private final ServerEventEmitter emitter;

    public CommerceTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
        this.product = new ProductTracker(emitter);
        this.cart = new CartTracker(emitter);
        this.checkout = new CheckoutTracker(emitter);
        this.purchase = new PurchaseTracker(emitter);
        this.recommendation = new RecommendationTracker(emitter);
        this.user = new UserTracker(emitter);
    }

    /**
     * Records an anonymous -> user association directly, for the case where a
     * merchant learns the mapping server-side (say, an OAuth callback) and has
     * no browser event to carry it.
     */
    public void identify(String anonymousId, String userId) {
        emitter.identify(anonymousId, userId);
    }

    /** Sends anything still buffered. Call before shutdown if async delivery is enabled. */
    public void flush() {
        emitter.flush();
    }
}
