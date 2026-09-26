// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.controller;

import io.omnirec.eventapi.ingest.EventIngestionService.EventPublishException;
import io.omnirec.eventapi.security.EventApiRequestFilter.PayloadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * One JSON error shape for the Event API — {@code {"status": 400, "error": "..."}} —
 * and the right status for each failure, because the SDKs decide whether to
 * retry from the status alone:
 *
 * <ul>
 *   <li>broker unavailable -> <b>503</b> with Retry-After (retry: nothing was lost)</li>
 *   <li>unparseable body -> <b>400</b> (don't retry: the bytes are wrong)</li>
 *   <li>oversized chunked body -> <b>413</b> (don't retry)</li>
 *   <li>unsupported content type -> <b>415</b> (don't retry)</li>
 * </ul>
 *
 * Messages never echo request content: a parse error message can quote the
 * offending value, and that value may be anything the caller sent.
 */
@RestControllerAdvice(assignableTypes = EventController.class)
public class EventApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EventApiExceptionHandler.class);

    @ExceptionHandler(EventPublishException.class)
    public ResponseEntity<Map<String, Object>> brokerUnavailable(EventPublishException e) {
        log.warn("Event queue unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, "event queue temporarily unavailable"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        if (causedBy(e, PayloadTooLargeException.class)) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload too large");
        }
        return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON of the expected shape");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "use application/json or text/plain");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return error(status, e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(body(status, message));
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        return Map.of("status", status.value(), "error", message);
    }

    private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
