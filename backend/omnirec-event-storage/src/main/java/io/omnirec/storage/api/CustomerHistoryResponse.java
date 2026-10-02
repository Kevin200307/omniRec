// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.storage.CustomerEventPage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Body of GET /v1/customers/{customerId}/events.
 *
 * <pre>
 * {
 *   "customerId": "user_456",
 *   "events": [
 *     { "eventId": "e101", "eventType": "product_viewed", "occurredAt": "2026-09-28T08:15:20Z",
 *       "productId": "P999", "anonymousId": "anon_123", "properties": {} }
 *   ],
 *   "nextCursor": "..."
 * }
 * </pre>
 *
 * An API shape, not a table shape: no tenant id (the caller knows which tenant
 * it asked for), no storage timestamps. Context is reduced to where the event
 * happened — the IP address and user agent are not returned, even when the
 * deployment retains them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerHistoryResponse(String customerId, List<HistoryEvent> events, String nextCursor) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryEvent(
            String eventId,
            /** Envelope v2 name. */
            String event,
            int eventVersion,
            String kind,
            String source,
            /** Same as {@code event}. Kept for v1 readers; removed in the release after 2.0. */
            String eventType,
            Instant occurredAt,
            Instant receivedAt,
            /** Set on every event; how a linked anonymous event is recognisable as one. */
            String anonymousId,
            /** Null on an anonymous event attributed to the customer through an identity link. */
            String userId,
            String sessionId,
            String productId,
            /** Envelope v2 payload. */
            Map<String, Object> data,
            /** The payload in the v1 flat shape, for v1 readers; removed in the release after 2.0. */
            CommerceData commerce,
            Map<String, Object> properties,
            HistoryContext context
    ) {
        @SuppressWarnings("deprecation")
        static HistoryEvent from(CommerceEvent event) {
            return new HistoryEvent(
                    event.eventId(),
                    event.eventType().wireName(),
                    event.eventVersion(),
                    event.kind(),
                    event.source() == null ? null : event.source().wireName(),
                    event.eventType().wireName(),
                    event.timestamp(),
                    event.receivedAt(),
                    event.identity().anonymousId(),
                    event.identity().userId(),
                    event.identity().sessionId(),
                    event.data().product().id(),
                    event.data().asMap(),
                    event.commerce(),
                    event.properties(),
                    HistoryContext.from(event.context()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryContext(String url, String path, String referrer, String platform, String device,
                                 String locale, String country) {
        static HistoryContext from(EventContext context) {
            return new HistoryContext(context.url(), context.path(), context.referrer(),
                    context.platform().wireName(), context.device().wireName(), context.locale(), context.country());
        }
    }

    public static CustomerHistoryResponse from(CustomerEventPage page) {
        return new CustomerHistoryResponse(
                page.customerId(),
                page.events().stream().map(HistoryEvent::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().encode());
    }
}
