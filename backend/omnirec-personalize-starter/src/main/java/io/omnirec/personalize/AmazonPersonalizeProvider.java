package io.omnirec.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.RecContext;
import io.omnirec.core.model.Recommendation;
import io.omnirec.core.provider.RecommendationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeevents.model.Event;
import software.amazon.awssdk.services.personalizeevents.model.PutEventsRequest;
import software.amazon.awssdk.services.personalizeruntime.PersonalizeRuntimeClient;
import software.amazon.awssdk.services.personalizeruntime.model.GetRecommendationsRequest;
import software.amazon.awssdk.services.personalizeruntime.model.PredictedItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The mapper from CanonicalEvent to Personalize's PutEvents shape, and from
 * PredictedItem back to our own Recommendation — this class is the entire
 * translation layer the design doc describes; nothing outside it knows
 * Personalize's request/response shapes exist.
 */
public class AmazonPersonalizeProvider implements RecommendationProvider {

    private static final Logger log = LoggerFactory.getLogger(AmazonPersonalizeProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PersonalizeRuntimeClient runtimeClient;
    private final PersonalizeEventsClient eventsClient;
    private final PersonalizeProperties properties;

    public AmazonPersonalizeProvider(PersonalizeRuntimeClient runtimeClient, PersonalizeEventsClient eventsClient, PersonalizeProperties properties) {
        this.runtimeClient = runtimeClient;
        this.eventsClient = eventsClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "aws-personalize";
    }

    @Override
    public void putEvents(List<CanonicalEvent> events) {
        if (properties.getTrackingId() == null) {
            log.warn("omnirec.recommendation.aws-personalize.tracking-id is not set — skipping putEvents");
            return;
        }
        for (CanonicalEvent event : events) {
            try {
                PutEventsRequest.Builder request = PutEventsRequest.builder()
                        .trackingId(properties.getTrackingId())
                        .sessionId(event.sessionId() != null ? event.sessionId() : event.anonymousId())
                        .eventList(toPersonalizeEvent(event));
                // Only a real, authenticated user. Sending the anonymousId as
                // userId minted a throwaway Personalize user per browser.
                if (event.userId() != null) {
                    request.userId(event.userId());
                }
                eventsClient.putEvents(request.build());
            } catch (Exception e) {
                log.warn("Failed to send event {} to AWS Personalize: {}", event.eventId(), e.getMessage());
            }
        }
    }

    /**
     * Pure CanonicalEvent → Personalize Event mapping, kept separate from
     * the network call so omnirec-contract-tests can assert on it directly
     * without a live AWS client or credentials.
     */
    public Event toPersonalizeEvent(CanonicalEvent event) throws com.fasterxml.jackson.core.JsonProcessingException {
        Event.Builder eventBuilder = Event.builder()
                .eventId(event.eventId())
                .eventType(event.eventType().name())
                .sentAt(event.context() != null && event.context().timestamp() != null ? event.context().timestamp() : Instant.now())
                .properties(MAPPER.writeValueAsString(event.payload()));

        Object productId = event.payload().get("productId");
        if (productId != null) {
            eventBuilder.itemId(productId.toString());
        }
        return eventBuilder.build();
    }

    @Override
    public List<Recommendation> getRecommendations(String userId, RecContext ctx) {
        if (properties.getCampaignArn() == null) {
            log.warn("omnirec.recommendation.aws-personalize.campaign-arn is not set — returning no recommendations");
            return List.of();
        }
        try {
            List<PredictedItem> items = runtimeClient.getRecommendations(GetRecommendationsRequest.builder()
                            .campaignArn(properties.getCampaignArn())
                            .userId(userId)
                            .numResults(ctx.numResults())
                            .build())
                    .itemList();

            return items.stream()
                    .map(item -> new Recommendation(
                            item.itemId(),
                            item.score() != null ? item.score() : 0.0,
                            item.hasMetadata() ? new java.util.LinkedHashMap<String, Object>(item.metadata()) : Map.of()
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("AWS Personalize getRecommendations failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
