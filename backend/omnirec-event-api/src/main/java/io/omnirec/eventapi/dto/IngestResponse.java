package io.omnirec.eventapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-event outcome of a batch.
 *
 * A batch is never all-or-nothing for permanent failures: one malformed event
 * among twenty must not discard the other nineteen. Rejected events are
 * reported individually and the SDK treats the response as success, because
 * resending a permanently bad event changes nothing.
 *
 * {@code retryLater} is different: those events are held by an in-progress
 * lease (the same event is being processed concurrently, or a previous attempt
 * crashed mid-flight). The controller answers 503 so the SDK retries the batch;
 * the events that were accepted this time come back as duplicates, which is
 * harmless.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record IngestResponse(int accepted, int rejected, int duplicates, int retryLater, List<EventError> errors) {

    public record EventError(String eventId, String reason) {
    }

    public static IngestResponse of(int accepted, int duplicates, int retryLater, List<EventError> errors) {
        return new IngestResponse(accepted, errors.size(), duplicates, retryLater, errors);
    }

    public boolean needsRetry() {
        return retryLater > 0;
    }
}
