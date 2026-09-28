// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import io.omnirec.commerce.storage.EventStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The Event API's error shape — {@code {"status": 400, "error": "..."}} — for
 * the history endpoint. Messages never echo request values.
 */
@RestControllerAdvice(assignableTypes = CustomerHistoryController.class)
public class CustomerHistoryExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerHistoryExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return error(status, e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    /** e.g. limit=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid value for " + e.getName());
    }

    @ExceptionHandler(EventStoreException.class)
    public ResponseEntity<Map<String, Object>> storeUnavailable(EventStoreException e) {
        log.warn("Customer history query failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("status", 503, "error", "history temporarily unavailable"));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", message));
    }
}
