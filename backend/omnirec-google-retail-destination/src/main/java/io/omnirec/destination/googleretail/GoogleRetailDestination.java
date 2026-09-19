package io.omnirec.destination.googleretail;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.retail.v2.UserEventServiceClient;
import com.google.cloud.retail.v2.WriteUserEventRequest;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers canonical events to Google Cloud Retail.
 *
 * Credentials are resolved by Application Default Credentials — workload
 * identity on GKE, a service account on Cloud Run, or
 * GOOGLE_APPLICATION_CREDENTIALS locally. No key material is configured here
 * and none of it is reachable from the browser.
 *
 * This class exists only because {@link GoogleRetailEventMapper} exists: the
 * mapper holds every Google-specific decision, and this is the thin network
 * shell around it. That split is what lets the mapping be tested exhaustively
 * with no GCP project.
 */
public class GoogleRetailDestination implements EventDestination {

    private static final Logger log = LoggerFactory.getLogger(GoogleRetailDestination.class);
    public static final String ID = "google-retail";

    private final UserEventServiceClient client;
    private final GoogleRetailProperties properties;
    private final GoogleRetailEventMapper mapper;

    public GoogleRetailDestination(
            UserEventServiceClient client,
            GoogleRetailProperties properties,
            GoogleRetailEventMapper mapper
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
     * Retail's event vocabulary is closed, so anything without a real
     * counterpart is filtered here rather than coerced into the nearest-looking
     * type. A dropped event is a gap; a wrong one is actively misleading.
     */
    @Override
    public boolean supports(CommerceEvent event) {
        return !event.eventType().isControlEvent() && mapper.supports(event);
    }

    @Override
    public void send(CommerceEvent event) {
        if (properties.getProjectNumber() == null || properties.getProjectNumber().isBlank()) {
            throw DestinationException.permanent(ID,
                    "omnirec.destinations.google-retail.project-number is not configured");
        }
        if (!mapper.supports(event)) {
            log.debug("No valid Google Retail event for {} — skipping", event.eventType().wireName());
            return;
        }

        try {
            client.writeUserEvent(WriteUserEventRequest.newBuilder()
                    .setParent(properties.catalogParent())
                    .setUserEvent(mapper.toUserEvent(event))
                    .build());
            log.debug("Delivered event {} to Google Retail as {}",
                    event.eventId(), mapper.retailEventType(event.eventType()));
        } catch (ApiException e) {
            throw classify(event, e);
        } catch (RuntimeException e) {
            throw new DestinationException(ID, "Google Retail call failed for event " + event.eventId(), e, true);
        }
    }

    /**
     * gRPC status codes carry an explicit retryability flag, so we take
     * Google's own judgement rather than guessing from the class of error.
     */
    private DestinationException classify(CommerceEvent event, ApiException e) {
        StatusCode.Code code = e.getStatusCode().getCode();
        boolean retryable = e.isRetryable()
                || code == StatusCode.Code.UNAVAILABLE
                || code == StatusCode.Code.DEADLINE_EXCEEDED
                || code == StatusCode.Code.RESOURCE_EXHAUSTED
                || code == StatusCode.Code.INTERNAL;

        String message = "Google Retail rejected event " + event.eventId() + " (" + code + ")";
        if (!retryable) {
            log.error("{} — will not retry", message);
        }
        return new DestinationException(ID, message, e, retryable);
    }
}
