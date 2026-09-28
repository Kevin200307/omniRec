// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.ingest;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.DeduplicationStore.ClaimResult;
import io.omnirec.commerce.identity.IdentityResolver;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationResult;
import io.omnirec.eventapi.dto.EventDto;
import io.omnirec.eventapi.dto.IngestResponse;
import io.omnirec.eventapi.normalize.EventNormalizer;
import io.omnirec.eventapi.queue.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The ingestion pipeline, in order:
 *
 * <pre>
 *   bind -> normalize -> validate -> lease -> resolve identity -> queue -> complete
 * </pre>
 *
 * The order is load-bearing:
 * <ul>
 *   <li><b>Bind per event</b>, so one malformed event can't fail the batch.</li>
 *   <li><b>Validate before leasing</b>, so a malformed event never occupies a
 *       deduplication key and a corrected resend under the same eventId gets
 *       through.</li>
 *   <li><b>Lease before identity</b>, so a redelivered {@code identify} doesn't
 *       re-link, and duplicate work stops as early as possible.</li>
 *   <li><b>Complete only after the broker accepted the event.</b> A crash
 *       before that leaves only a short lease behind, which expires; the key
 *       never claims "done" for an event that was never queued.</li>
 * </ul>
 */
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);
    private static final String DEDUP_STAGE = "ingest";

    /**
     * How long an in-flight ingestion holds its lease. Long enough to cover a
     * slow broker publish; short enough that a crashed request blocks its
     * client's retry for seconds, not hours.
     */
    static final Duration INGEST_LEASE = Duration.ofSeconds(30);

    private final EventNormalizer normalizer;
    private final EventValidator validator;
    private final DeduplicationStore deduplicationStore;
    private final IdentityResolver identityResolver;
    private final EventPublisher publisher;
    private final EventMetrics metrics;
    private final Duration deduplicationWindow;
    private final ObjectMapper objectMapper;

    public EventIngestionService(
            EventNormalizer normalizer,
            EventValidator validator,
            DeduplicationStore deduplicationStore,
            IdentityResolver identityResolver,
            EventPublisher publisher,
            EventMetrics metrics,
            Duration deduplicationWindow,
            ObjectMapper objectMapper
    ) {
        this.normalizer = normalizer;
        this.validator = validator;
        this.deduplicationStore = deduplicationStore;
        this.identityResolver = identityResolver;
        this.publisher = publisher;
        this.metrics = metrics;
        this.deduplicationWindow = deduplicationWindow;
        this.objectMapper = objectMapper;
    }

    /** Raw JSON events, as received from the wire. */
    public IngestResponse ingestJson(List<JsonNode> events, String tenantId, EventNormalizer.RequestMetadata request) {
        metrics.eventsReceived(tenantId, events.size());
        Tally tally = new Tally();

        for (JsonNode node : events) {
            EventDto dto;
            try {
                dto = objectMapper.treeToValue(node, EventDto.class);
            } catch (Exception e) {
                metrics.eventsRejected(tenantId, "malformed", 1);
                tally.errors.add(new IngestResponse.EventError(eventIdOf(node), describeBindingFailure(e)));
                continue;
            }
            ingestOne(dto, tenantId, request, tally);
        }
        return tally.toResponse();
    }

    /** Already-typed events, e.g. from the /v1/identify shim. */
    public IngestResponse ingest(List<EventDto> events, String tenantId, EventNormalizer.RequestMetadata request) {
        metrics.eventsReceived(tenantId, events.size());
        Tally tally = new Tally();
        for (EventDto dto : events) {
            ingestOne(dto, tenantId, request, tally);
        }
        return tally.toResponse();
    }

    private void ingestOne(EventDto dto, String tenantId, EventNormalizer.RequestMetadata request, Tally tally) {
        CommerceEvent event;
        try {
            event = normalizer.normalize(dto, tenantId, request);
        } catch (RuntimeException e) {
            // Never echo the payload back — it may hold whatever the merchant put in it.
            metrics.eventsRejected(tenantId, "malformed", 1);
            tally.errors.add(new IngestResponse.EventError(dto.eventId(), "malformed event"));
            return;
        }

        ValidationResult validation = validator.validate(event);
        if (!validation.valid()) {
            metrics.eventsRejected(tenantId, "invalid", 1);
            tally.errors.add(new IngestResponse.EventError(event.eventId(), validation.describe()));
            log.debug("Rejected event {} for tenant {}: {}", event.eventId(), tenantId, validation.describe());
            return;
        }
        metrics.eventsValidated(tenantId, 1);

        String dedupKey = DeduplicationStore.key(DEDUP_STAGE, tenantId, event.eventId());
        ClaimResult claim = deduplicationStore.claim(dedupKey, INGEST_LEASE);
        if (claim == ClaimResult.ALREADY_COMPLETED) {
            metrics.duplicateEvents(tenantId, DEDUP_STAGE, 1);
            log.debug("Dropped duplicate event {} for tenant {}", event.eventId(), tenantId);
            tally.duplicates++;
            return;
        }
        if (claim == ClaimResult.IN_PROGRESS) {
            // Being ingested right now by another request — or a previous
            // attempt crashed holding the lease. Either way, not a duplicate
            // yet: ask the client to retry once the lease resolves.
            tally.retryLater++;
            return;
        }

        try {
            // Returns null for a control event (identify): the link is recorded
            // and there is nothing behavioural left to deliver to a provider.
            CommerceEvent resolved = identityResolver.process(event);
            if (resolved != null) {
                publisher.publish(resolved);
                metrics.eventsQueued(tenantId, 1);
            } else {
                // Still handed to the publisher, which routes a control event
                // only to destinations that accept one (historical storage
                // records the link from it). No provider destination does, so
                // with storage off this publishes nothing.
                publisher.publish(event);
            }
            deduplicationStore.complete(dedupKey, deduplicationWindow);
            tally.accepted++;
        } catch (RuntimeException e) {
            // The broker is unreachable or refused the message. Release the
            // lease so the client's retry isn't mistaken for a duplicate.
            metrics.eventsFailed(tenantId, "publish", 1);
            releaseQuietly(dedupKey);
            throw new EventPublishException("Failed to queue event " + event.eventId(), e);
        }
    }

    private void releaseQuietly(String dedupKey) {
        try {
            deduplicationStore.release(dedupKey);
        } catch (RuntimeException e) {
            // The lease expires on its own; failing the request twice helps nobody.
            log.debug("Could not release deduplication lease after publish failure", e);
        }
    }

    /**
     * Names the field that failed to bind, never its value: the value may be
     * anything the merchant sent, including data that must not be echoed.
     */
    private static String describeBindingFailure(Exception e) {
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."))
                    .replace(".[", "[");
            return "malformed event: invalid value for " + field;
        }
        return "malformed event";
    }

    private static String eventIdOf(JsonNode node) {
        JsonNode id = node == null ? null : node.get("eventId");
        return id != null && id.isTextual() ? id.asText() : null;
    }

    private static final class Tally {
        int accepted;
        int duplicates;
        int retryLater;
        final List<IngestResponse.EventError> errors = new ArrayList<>();

        IngestResponse toResponse() {
            return IngestResponse.of(accepted, duplicates, retryLater, errors);
        }
    }

    /** Signals a broker failure, mapped to 503 so the client retries rather than giving up. */
    public static class EventPublishException extends RuntimeException {
        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
