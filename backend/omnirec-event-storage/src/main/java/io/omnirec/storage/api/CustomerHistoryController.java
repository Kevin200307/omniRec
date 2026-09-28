// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventCursor;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.storage.config.EventStorageProperties;
import io.omnirec.storage.metrics.StorageMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GET /v1/customers/{customerId}/events — a customer's journey, newest first:
 * their authenticated events merged with the anonymous events of every device
 * linked to them.
 *
 * <pre>
 *   ?limit=50                          page size (default and maximum configurable)
 *   &amp;cursor=...                        the previous page's nextCursor
 *   &amp;from=2026-09-01T00:00:00Z         inclusive
 *   &amp;to=2026-10-01T00:00:00Z           exclusive
 *   &amp;eventType=product_viewed          repeatable, or comma-separated
 * </pre>
 *
 * The tenant comes only from {@link CustomerHistoryAuthFilter}, never from the
 * request. An unknown customer is an empty page rather than a 404: the API
 * cannot tell "no such customer" from "no history yet", and should not pretend to.
 */
@RestController
public class CustomerHistoryController {

    private static final Logger log = LoggerFactory.getLogger(CustomerHistoryController.class);

    static final int MAX_CUSTOMER_ID_LENGTH = 256;

    private final EventStore store;
    private final EventStorageProperties.HistoryApi config;
    private final StorageMetrics metrics;

    public CustomerHistoryController(EventStore store, EventStorageProperties.HistoryApi config, StorageMetrics metrics) {
        this.store = store;
        this.config = config;
        this.metrics = metrics;
    }

    @GetMapping(value = "/v1/customers/{customerId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public CustomerHistoryResponse events(
            @PathVariable String customerId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "eventType", required = false) List<String> eventTypes,
            HttpServletRequest request
    ) {
        String tenantId = tenantOf(request);
        validateCustomerId(customerId);

        EventQuery query;
        try {
            query = new EventQuery(
                    pageSize(limit),
                    cursor == null || cursor.isBlank() ? null : EventCursor.decode(cursor),
                    instant("from", from),
                    instant("to", to),
                    eventTypes(eventTypes));
        } catch (IllegalArgumentException e) {
            // Cursor tampering or from >= to. The message is ours, not the caller's input.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        long started = System.nanoTime();
        CustomerEventPage page = store.findCustomerEvents(tenantId, customerId, query);
        metrics.historyQuery(Duration.ofNanos(System.nanoTime() - started));

        // The customer id is personal data: log who asked and how much came back, not whom it was about.
        log.debug("History read by {} for tenant {}: {} event(s), more={}",
                request.getAttribute(CustomerHistoryAuthFilter.PRINCIPAL_ATTRIBUTE), tenantId,
                page.events().size(), page.hasMore());
        return CustomerHistoryResponse.from(page);
    }

    /** Fail closed if the filter somehow did not run: never serve history without an authorised tenant. */
    private static String tenantOf(HttpServletRequest request) {
        Object tenant = request.getAttribute(CustomerHistoryAuthFilter.TENANT_ATTRIBUTE);
        if (tenant instanceof String tenantId && !tenantId.isBlank()) {
            return tenantId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or missing secret key");
    }

    private static void validateCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank() || customerId.length() > MAX_CUSTOMER_ID_LENGTH
                || customerId.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "customerId must be 1-" + MAX_CUSTOMER_ID_LENGTH + " printable characters");
        }
    }

    private int pageSize(Integer limit) {
        if (limit == null) return Math.min(config.getDefaultLimit(), config.getMaxLimit());
        if (limit < 1 || limit > config.getMaxLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + config.getMaxLimit());
        }
        return limit;
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must be an ISO-8601 instant, e.g. 2026-09-28T00:00:00Z");
        }
    }

    private static Set<EventType> eventTypes(List<String> values) {
        Set<EventType> types = new LinkedHashSet<>();
        if (values == null) return types;
        for (String value : values) {
            for (String name : value.split(",")) {
                if (name.isBlank()) continue;
                types.add(EventType.find(name.trim()).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown eventType")));
            }
        }
        return types;
    }
}
