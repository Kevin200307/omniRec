// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.personalize;

import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeevents.model.Event;
import software.amazon.awssdk.services.personalizeevents.model.PersonalizeEventsException;
import software.amazon.awssdk.services.personalizeevents.model.PutEventsRequest;

import java.util.List;
import java.util.Set;

/**
 * Delivers canonical events to Amazon Personalize.
 *
 * Credentials never come from the browser or from an event payload — they are
 * resolved server-side by the AWS SDK's default provider chain (IAM role,
 * instance profile, environment), which is why the frontend only ever holds a
 * publishable key.
 *
 * Failure classification decides retry versus dead-letter:
 * <ul>
 *   <li>throttling, 5xx, and network errors -> retryable;</li>
 *   <li>validation errors -> permanent, because the same payload against the
 *       same tracker will fail identically forever.</li>
 * </ul>
 */
public class AmazonPersonalizeDestination implements EventDestination {

    private static final Logger log = LoggerFactory.getLogger(AmazonPersonalizeDestination.class);
    public static final String ID = "amazon-personalize";

    /** PutEvents limit: https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_PutEvents.html */
    static final int MAX_EVENTS_PER_CALL = 10;
    private static final int MAX_ID_LENGTH = 256;

    /**
     * Event types with no useful analogue in an interactions dataset. Sending
     * them costs money and dilutes the signal, so they're filtered before any
     * mapping work happens.
     */
    private static final Set<EventType> UNSUPPORTED = Set.of(
            EventType.SESSION_STARTED,
            EventType.SESSION_ENDED,
            EventType.USER_LOGGED_OUT,
            EventType.USER_PROFILE_UPDATED,
            EventType.IDENTIFY
    );

    private final PersonalizeEventsClient client;
    private final AmazonPersonalizeProperties properties;
    private final AmazonPersonalizeEventMapper mapper;

    public AmazonPersonalizeDestination(
            PersonalizeEventsClient client,
            AmazonPersonalizeProperties properties,
            AmazonPersonalizeEventMapper mapper
    ) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Engagement updates (dwell time on an earlier view) are skipped: sent as
     * an interaction, every product view would be counted twice.
     */
    @Override
    public boolean supports(CommerceEvent event) {
        return !event.eventType().isControlEvent()
                && !UNSUPPORTED.contains(event.eventType())
                && !event.isEngagementUpdate();
    }

    @Override
    public void send(CommerceEvent event) {
        if (properties.getTrackingId() == null || properties.getTrackingId().isBlank()) {
            throw DestinationException.permanent(ID,
                    "omnirec.destinations.amazon-personalize.tracking-id is not configured");
        }

        List<Event> personalizeEvents = mapper.toPersonalizeEvents(event);

        try {
            // At most 10 events per call. If a later chunk fails, the retry
            // resends earlier chunks too — safe, because Personalize ignores
            // repeats of an eventId for training.
            for (int from = 0; from < personalizeEvents.size(); from += MAX_EVENTS_PER_CALL) {
                List<Event> chunk = personalizeEvents.subList(from,
                        Math.min(from + MAX_EVENTS_PER_CALL, personalizeEvents.size()));
                client.putEvents(request(event, chunk));
            }
            log.debug("Delivered {} interaction(s) for event {} to Personalize",
                    personalizeEvents.size(), event.eventId());
        } catch (PersonalizeEventsException e) {
            throw classify(event, e);
        } catch (SdkClientException e) {
            // Connection, DNS, timeout — always worth another attempt.
            throw new DestinationException(ID, "Personalize call failed for event " + event.eventId(), e, true);
        }
    }

    private PutEventsRequest request(CommerceEvent event, List<Event> events) {
        PutEventsRequest.Builder request = PutEventsRequest.builder()
                .trackingId(properties.getTrackingId())
                .sessionId(limit(resolveSessionId(event)))
                .eventList(events);

        // userId only when the visitor is genuinely authenticated. Passing the
        // anonymousId would mint a throwaway Personalize user per browser that
        // never reconciles with the real customer. Personalize instead stitches
        // the anonymous session to the user when a later call carries both.
        if (event.identity().isAuthenticated()) {
            request.userId(limit(event.identity().userId()));
        }
        return request.build();
    }

    /**
     * Personalize requires a sessionId on every call. An event that somehow
     * lacks one still needs to be deliverable, so we fall back to the
     * anonymousId — stable per device, the closest honest equivalent we have.
     */
    private String resolveSessionId(CommerceEvent event) {
        String sessionId = event.identity().sessionId();
        return sessionId != null && !sessionId.isBlank() ? sessionId : event.identity().anonymousId();
    }

    private static String limit(String value) {
        return value.length() <= MAX_ID_LENGTH ? value : value.substring(0, MAX_ID_LENGTH);
    }

    private DestinationException classify(CommerceEvent event, PersonalizeEventsException e) {
        int status = e.statusCode();
        boolean retryable = e.isThrottlingException() || status >= 500 || status == 0;
        String message = "Personalize rejected event " + event.eventId() + " (HTTP " + status + ")";

        if (!retryable) {
            log.error("{} — will not retry: {}", message, e.awsErrorDetails() == null
                    ? e.getMessage() : e.awsErrorDetails().errorMessage());
        }
        return new DestinationException(ID, message, e, retryable);
    }
}
