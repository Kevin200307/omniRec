package io.omnirec.googlerecai;

import com.google.cloud.retail.v2.*;
import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.EventType;
import io.omnirec.core.model.RecContext;
import io.omnirec.core.model.Recommendation;
import io.omnirec.core.provider.RecommendationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Maps CanonicalEvent to Retail API's UserEvent (a fixed vocabulary of
 * event type strings, unlike Personalize's free-form ones) and
 * PredictionResult back to Recommendation. This mapper — not
 * PersonalizationService, not the frontend — is the only place that
 * vocabulary difference between the two providers is visible.
 */
public class GoogleRecommendationsAiProvider implements RecommendationProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleRecommendationsAiProvider.class);

    private final UserEventServiceClient userEventClient;
    private final PredictionServiceClient predictionClient;
    private final GoogleRecAiProperties properties;

    public GoogleRecommendationsAiProvider(UserEventServiceClient userEventClient, PredictionServiceClient predictionClient, GoogleRecAiProperties properties) {
        this.userEventClient = userEventClient;
        this.predictionClient = predictionClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "google-rec-ai";
    }

    @Override
    public void putEvents(List<CanonicalEvent> events) {
        for (CanonicalEvent event : events) {
            try {
                userEventClient.writeUserEvent(WriteUserEventRequest.newBuilder()
                        .setParent(properties.eventStoreParent())
                        .setUserEvent(toUserEvent(event))
                        .build());
            } catch (Exception e) {
                log.warn("Failed to send event {} to Google Rec AI: {}", event.eventId(), e.getMessage());
            }
        }
    }

    /**
     * Pure CanonicalEvent → Retail API UserEvent mapping, kept separate
     * from the network call so omnirec-contract-tests can assert on it
     * directly without live GCP credentials. This is also where the
     * eventType vocabulary translation (mapEventType) lives.
     */
    public UserEvent toUserEvent(CanonicalEvent event) {
        UserEvent.Builder builder = UserEvent.newBuilder()
                .setEventType(mapEventType(event.eventType()))
                .setVisitorId(event.userId() != null ? event.userId() : event.anonymousId())
                .setSessionId(event.sessionId() != null ? event.sessionId() : "");

        Object productId = event.payload().get("productId");
        if (productId != null) {
            builder.addProductDetails(ProductDetail.newBuilder()
                    .setProduct(Product.newBuilder().setId(productId.toString()).build())
                    .build());
        }
        return builder.build();
    }

    @Override
    public List<Recommendation> getRecommendations(String userId, RecContext ctx) {
        try {
            UserEvent userEvent = UserEvent.newBuilder()
                    .setEventType("home-page-view")
                    .setVisitorId(userId)
                    .build();

            PredictResponse response = predictionClient.predict(PredictRequest.newBuilder()
                    .setPlacement(properties.placement())
                    .setUserEvent(userEvent)
                    .setPageSize(ctx.numResults())
                    .build());

            List<PredictResponse.PredictionResult> results = response.getResultsList();
            // Retail API doesn't expose a raw relevance score on PredictionResult the way
            // Personalize does — results already arrive ranked, so we derive a descending
            // score from rank purely so PersonalizationService's cross-provider merge/sort
            // (which sorts by score) preserves Google's own ordering.
            return java.util.stream.IntStream.range(0, results.size())
                    .mapToObj(i -> new Recommendation(results.get(i).getId(), results.size() - i, Map.of()))
                    .toList();
        } catch (Exception e) {
            log.warn("Google Rec AI predict failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private String mapEventType(EventType eventType) {
        return switch (eventType) {
            case PRODUCT_VIEWED, PRODUCT_CLICKED, PRODUCT_DWELL -> "detail-page-view";
            case CART_ADD -> "add-to-cart";
            case CART_REMOVE, CART_ABANDONED -> "add-to-cart";
            case PURCHASE_COMPLETED -> "purchase-complete";
            case SEARCH_QUERY -> "search";
            case PAGE_VIEW -> "home-page-view";
            default -> "home-page-view";
        };
    }
}
