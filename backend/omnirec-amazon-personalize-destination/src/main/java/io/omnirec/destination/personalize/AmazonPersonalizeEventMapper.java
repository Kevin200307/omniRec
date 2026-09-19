package io.omnirec.destination.personalize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.personalizeevents.model.Event;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps the canonical {@link CommerceEvent} onto Amazon Personalize's {@code Event}.
 * Pure translation, no network calls, so it is asserted on directly with no AWS
 * account.
 *
 * Every constraint here comes from the Event and PutEvents API references:
 * https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_Event.html
 *
 * <ul>
 *   <li><b>One itemId per event</b>, so a multi-line order becomes one event per
 *       line, each with its own eventId (Personalize discards repeats of an
 *       eventId from training).</li>
 *   <li><b>{@code properties}</b> is a JSON string map of at most 1024 chars
 *       whose keys must be fields of <em>your</em> interactions schema, and it
 *       may not contain userId, sessionId, eventType, timestamp,
 *       recommendationId, or impression. So we send only the keys the operator
 *       allow-lists (see {@code property-keys}); by default, none.</li>
 *   <li><b>{@code recommendationId}</b> (≤40 chars) is Personalize's own
 *       attribution field. It is only valid for a list Personalize served, so
 *       it is set only when {@code commerce.recommendationProvider} says so.</li>
 *   <li><b>{@code impression}</b> is at most 25 item ids.</li>
 * </ul>
 */
public class AmazonPersonalizeEventMapper {

    private static final Logger log = LoggerFactory.getLogger(AmazonPersonalizeEventMapper.class);

    public static final String PROVIDER_NAME = "amazon-personalize";
    static final int MAX_PROPERTIES_LENGTH = 1024;
    static final int MAX_IMPRESSION_ITEMS = 25;
    static final int MAX_RECOMMENDATION_ID_LENGTH = 40;
    static final int MAX_ID_LENGTH = 256;

    /** Keywords the API forbids inside {@code properties}, compared case-insensitively. */
    private static final Set<String> RESERVED_PROPERTY_KEYS =
            Set.of("userid", "sessionid", "eventtype", "timestamp", "recommendationid", "impression");

    private final ObjectMapper objectMapper;
    private final Set<String> allowedPropertyKeys;

    public AmazonPersonalizeEventMapper(ObjectMapper objectMapper) {
        this(objectMapper, Set.of());
    }

    public AmazonPersonalizeEventMapper(ObjectMapper objectMapper, Set<String> allowedPropertyKeys) {
        this.objectMapper = objectMapper;
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String key : allowedPropertyKeys) {
            if (RESERVED_PROPERTY_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                // Failing at startup beats every event being rejected at runtime.
                throw new IllegalArgumentException(
                        "'" + key + "' is reserved by Personalize and cannot be sent in properties");
            }
            allowed.add(key);
        }
        this.allowedPropertyKeys = Set.copyOf(allowed);
    }

    /**
     * One Personalize event per interaction. Usually one, but a multi-line
     * order becomes one per line.
     */
    public List<Event> toPersonalizeEvents(CommerceEvent event) {
        List<CommerceItem> items = event.commerce().items();
        String properties = serializeProperties(event);

        if (items == null || items.size() <= 1) {
            return List.of(buildEvent(event, event.eventId(), resolveItemId(event), resolveEventValue(event), properties));
        }

        List<Event> events = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            CommerceItem item = items.get(i);
            BigDecimal lineValue = item.price() == null ? null
                    : item.quantity() == null ? item.price()
                    : item.price().multiply(BigDecimal.valueOf(item.quantity()));
            // Every line needs its own eventId, or Personalize keeps only the first.
            events.add(buildEvent(event, event.eventId() + ":" + i, item.productId(), lineValue, properties));
        }
        return events;
    }

    /** Single-event convenience for the common case and for the contract tests. */
    public Event toPersonalizeEvent(CommerceEvent event) {
        return toPersonalizeEvents(event).get(0);
    }

    private Event buildEvent(CommerceEvent event, String eventId, String itemId, BigDecimal value, String properties) {
        Event.Builder builder = Event.builder()
                .eventId(truncate(eventId, MAX_ID_LENGTH))
                .eventType(event.eventType().wireName())
                .sentAt(event.timestamp());

        if (itemId != null) builder.itemId(truncate(itemId, MAX_ID_LENGTH));
        if (value != null) builder.eventValue(value.floatValue());
        if (properties != null) builder.properties(properties);

        List<String> impression = resolveImpression(event);
        if (!impression.isEmpty()) builder.impression(impression);

        String recommendationId = resolveRecommendationId(event.commerce());
        if (recommendationId != null) builder.recommendationId(recommendationId);

        return builder.build();
    }

    private String resolveItemId(CommerceEvent event) {
        if (event.commerce().productId() != null) return event.commerce().productId();
        List<CommerceItem> items = event.commerce().items();
        return items != null && !items.isEmpty() ? items.get(0).productId() : null;
    }

    /** Personalize uses eventValue to weight and threshold interactions. */
    private BigDecimal resolveEventValue(CommerceEvent event) {
        return event.commerce().total() != null ? event.commerce().total() : event.commerce().price();
    }

    /**
     * What the shopper was shown but did not pick — the signal that makes a
     * click informative. Capped at the API's 25.
     */
    private List<String> resolveImpression(CommerceEvent event) {
        if (event.eventType() != EventType.RECOMMENDATION_IMPRESSION
                && event.eventType() != EventType.PRODUCT_LIST_VIEWED) {
            return List.of();
        }
        List<String> productIds = event.commerce().productIds();
        if (productIds == null || productIds.isEmpty()) return List.of();
        return productIds.size() > MAX_IMPRESSION_ITEMS ? productIds.subList(0, MAX_IMPRESSION_ITEMS) : productIds;
    }

    /**
     * Only forwarded when Personalize itself served the list. Another engine's
     * id in this field is meaningless to Personalize at best and pollutes its
     * attribution metrics at worst.
     */
    private String resolveRecommendationId(CommerceData commerce) {
        String id = commerce.recommendationId();
        if (id == null || !PROVIDER_NAME.equals(commerce.recommendationProvider())) return null;
        if (id.length() > MAX_RECOMMENDATION_ID_LENGTH) {
            log.debug("recommendationId longer than {} chars — not forwarded as attribution", MAX_RECOMMENDATION_ID_LENGTH);
            return null;
        }
        return id;
    }

    /**
     * Only allow-listed keys, drawn from the commerce fields and merchant
     * properties, as the string map the API expects. If the result would
     * exceed 1024 chars the blob is dropped rather than truncated: a truncated
     * JSON string is invalid and would fail the whole call, while the
     * interaction itself — the part the model learns from — is still worth
     * delivering.
     */
    String serializeProperties(CommerceEvent event) {
        if (allowedPropertyKeys.isEmpty()) return null;

        Map<String, Object> candidates = new LinkedHashMap<>();
        CommerceData c = event.commerce();
        putIfPresent(candidates, "categoryId", c.categoryId());
        putIfPresent(candidates, "category", c.category());
        putIfPresent(candidates, "currency", c.currency());
        putIfPresent(candidates, "quantity", c.quantity());
        putIfPresent(candidates, "cartId", c.cartId());
        putIfPresent(candidates, "orderId", c.orderId());
        putIfPresent(candidates, "searchQuery", c.searchQuery());
        putIfPresent(candidates, "listId", c.listId());
        event.properties().forEach((key, value) -> candidates.putIfAbsent(key, value));

        Map<String, String> selected = new LinkedHashMap<>();
        for (String key : allowedPropertyKeys) {
            Object value = candidates.get(key);
            if (value != null && !(value instanceof Map) && !(value instanceof java.util.Collection)) {
                selected.put(key, String.valueOf(value));
            }
        }
        if (selected.isEmpty()) return null;

        try {
            String json = objectMapper.writeValueAsString(selected);
            if (json.length() > MAX_PROPERTIES_LENGTH) {
                log.warn("Personalize properties for event {} exceed {} chars — sending the interaction without them",
                        event.eventId(), MAX_PROPERTIES_LENGTH);
                return null;
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise properties for event {} — sending the interaction without them",
                    event.eventId());
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
