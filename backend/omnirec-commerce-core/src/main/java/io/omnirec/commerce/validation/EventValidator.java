package io.omnirec.commerce.validation;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.validation.ValidationResult.ValidationError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Server-side validation. Mirrors {@code packages/commerce-web/src/validation/validator.ts}
 * rule for rule — the contract test asserts the two agree.
 *
 * This runs even though the SDK already validated, because a client-side check
 * is a developer convenience, not a guarantee: anyone can POST to the Event API
 * directly. The SDK's copy exists so mistakes surface in the console during
 * development; this copy exists because it is the actual boundary.
 */
public class EventValidator {

    /**
     * Field names that must never appear in an event, at any depth. Matching is
     * on a normalised name (lowercased, non-alphanumerics stripped), so
     * {@code card_number}, {@code cardNumber} and {@code CardNumber} all match.
     *
     * This is a hard rejection, not a redaction: quietly stripping the field
     * would leave the merchant believing the data was accepted, and they'd
     * never fix the call site.
     */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "cardnumber", "cardno", "pan", "cvv", "cvc", "cvv2",
            "securitycode", "cardsecuritycode", "expirymonth", "expiryyear", "cardexpiry",
            "password", "passwd", "pin", "ssn", "socialsecuritynumber",
            "accesstoken", "refreshtoken", "apikey", "apisecret", "secretkey",
            "privatekey", "authorization", "creditcard", "iban"
    );

    private static final Pattern ISO_4217 = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final int MAX_WALK_DEPTH = 12;

    private final Map<EventType, List<BiConsumer<CommerceEvent, List<ValidationError>>>> rules = buildRules();

    public ValidationResult validate(CommerceEvent event) {
        List<ValidationError> errors = new ArrayList<>();

        validateUniversal(event, errors);
        for (var rule : rules.getOrDefault(event.eventType(), List.of())) {
            rule.accept(event, errors);
        }
        assertNoSensitiveFields(event, errors);

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.invalid(errors);
    }

    private void validateUniversal(CommerceEvent event, List<ValidationError> errors) {
        if (isBlank(event.eventId())) {
            errors.add(new ValidationError("eventId", "eventId is required"));
        }
        if (event.eventType() == null) {
            errors.add(new ValidationError("eventType", "eventType is required"));
        }
        if (isBlank(event.schemaVersion())) {
            errors.add(new ValidationError("schemaVersion", "schemaVersion is required"));
        }
        if (event.timestamp() == null) {
            errors.add(new ValidationError("timestamp", "timestamp must be a valid ISO-8601 date-time"));
        }
        if (event.identity() == null || isBlank(event.identity().anonymousId())) {
            errors.add(new ValidationError("identity.anonymousId", "anonymousId is required"));
        }
        if (event.identity() == null || isBlank(event.identity().sessionId())) {
            errors.add(new ValidationError("identity.sessionId", "sessionId is required"));
        }
    }

    private Map<EventType, List<BiConsumer<CommerceEvent, List<ValidationError>>>> buildRules() {
        Map<EventType, List<BiConsumer<CommerceEvent, List<ValidationError>>>> map = new EnumMap<>(EventType.class);

        map.put(EventType.SEARCH_PERFORMED, List.of(requireSearchQuery()));
        map.put(EventType.SEARCH_RESULT_CLICKED, List.of(requireSearchQuery(), requireProductId()));
        map.put(EventType.PRODUCT_LIST_VIEWED, List.of(requireProductIds()));
        map.put(EventType.CATEGORY_VIEWED, List.of(requireCategoryId()));
        map.put(EventType.PRODUCT_VIEWED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_CLICKED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_WISHLISTED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_SHARED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_COMPARED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_REVIEW_VIEWED, List.of(requireProductId()));
        map.put(EventType.PRODUCT_REVIEW_SUBMITTED, List.of(requireProductId()));
        map.put(EventType.CART_VIEWED, List.of(requireCartId()));
        map.put(EventType.PRODUCT_ADDED_TO_CART, List.of(requireProductId(), requirePositiveQuantity()));
        map.put(EventType.PRODUCT_REMOVED_FROM_CART, List.of(requireProductId()));
        map.put(EventType.CART_QUANTITY_UPDATED, List.of(requireProductId()));
        map.put(EventType.CART_ABANDONED, List.of(requireCartId()));
        map.put(EventType.CHECKOUT_STARTED, List.of(requireCartId()));
        map.put(EventType.SHIPPING_INFORMATION_ADDED, List.of(requireCartId()));
        map.put(EventType.PAYMENT_INFORMATION_ADDED, List.of(requireCartId()));
        map.put(EventType.CHECKOUT_COMPLETED, List.of(requireCartId()));
        map.put(EventType.CHECKOUT_FAILED, List.of(requireCartId()));
        map.put(EventType.PURCHASE_COMPLETED,
                List.of(requireOrderId(), requireItems(), requireCurrency(), requireTotal()));
        map.put(EventType.PURCHASE_FAILED, List.of(requireOrderId()));
        map.put(EventType.ORDER_CANCELLED, List.of(requireOrderId()));
        map.put(EventType.ORDER_REFUNDED, List.of(requireOrderId()));
        map.put(EventType.RECOMMENDATION_IMPRESSION, List.of(requireRecommendationId(), requireProductIds()));
        map.put(EventType.RECOMMENDATION_CLICKED, List.of(requireRecommendationId(), requireProductId()));
        map.put(EventType.RECOMMENDATION_ADDED_TO_CART, List.of(requireRecommendationId(), requireProductId()));
        map.put(EventType.RECOMMENDATION_PURCHASED, List.of(requireRecommendationId(), requireProductId()));
        map.put(EventType.USER_REGISTERED, List.of(requireUserId()));
        map.put(EventType.USER_LOGGED_IN, List.of(requireUserId()));
        map.put(EventType.USER_PROFILE_UPDATED, List.of(requireUserId()));
        map.put(EventType.IDENTIFY, List.of(requireUserId()));

        return map;
    }

    // --- individual rules -------------------------------------------------

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireProductId() {
        return (event, errors) -> {
            if (isBlank(commerce(event).productId())) {
                errors.add(new ValidationError("commerce.productId", "productId is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireCartId() {
        return (event, errors) -> {
            if (isBlank(commerce(event).cartId())) {
                errors.add(new ValidationError("commerce.cartId", "cartId is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireOrderId() {
        return (event, errors) -> {
            if (isBlank(commerce(event).orderId())) {
                errors.add(new ValidationError("commerce.orderId", "orderId is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireSearchQuery() {
        return (event, errors) -> {
            if (isBlank(commerce(event).searchQuery())) {
                errors.add(new ValidationError("commerce.searchQuery", "query is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireCategoryId() {
        return (event, errors) -> {
            if (isBlank(commerce(event).categoryId())) {
                errors.add(new ValidationError("commerce.categoryId", "categoryId is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireRecommendationId() {
        return (event, errors) -> {
            if (isBlank(commerce(event).recommendationId())) {
                errors.add(new ValidationError("commerce.recommendationId", "recommendationId is required"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireProductIds() {
        return (event, errors) -> {
            List<String> productIds = commerce(event).productIds();
            if (productIds == null || productIds.isEmpty()) {
                errors.add(new ValidationError("commerce.productIds", "productIds is required and must be non-empty"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requirePositiveQuantity() {
        return (event, errors) -> {
            Integer quantity = commerce(event).quantity();
            if (quantity == null) {
                errors.add(new ValidationError("commerce.quantity", "quantity is required"));
            } else if (quantity <= 0) {
                errors.add(new ValidationError("commerce.quantity", "quantity must be greater than 0"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireCurrency() {
        return (event, errors) -> {
            String currency = commerce(event).currency();
            if (isBlank(currency)) {
                errors.add(new ValidationError("commerce.currency", "currency is required"));
            } else if (!ISO_4217.matcher(currency).matches()) {
                errors.add(new ValidationError("commerce.currency", "currency must be a 3-letter ISO 4217 code"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireTotal() {
        return (event, errors) -> {
            BigDecimal total = commerce(event).total();
            if (total == null) {
                errors.add(new ValidationError("commerce.total", "total is required"));
            } else if (total.signum() < 0) {
                errors.add(new ValidationError("commerce.total", "total must be a non-negative number"));
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireItems() {
        return (event, errors) -> {
            List<CommerceItem> items = commerce(event).items();
            if (items == null || items.isEmpty()) {
                errors.add(new ValidationError("commerce.items", "items is required and must be non-empty"));
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                CommerceItem item = items.get(i);
                if (item == null || isBlank(item.productId())) {
                    errors.add(new ValidationError("commerce.items[" + i + "].productId", "productId is required"));
                }
                if (item != null && item.quantity() != null && item.quantity() <= 0) {
                    errors.add(new ValidationError("commerce.items[" + i + "].quantity",
                            "quantity must be greater than 0"));
                }
            }
        };
    }

    private static BiConsumer<CommerceEvent, List<ValidationError>> requireUserId() {
        return (event, errors) -> {
            if (event.identity() == null || isBlank(event.identity().userId())) {
                errors.add(new ValidationError("identity.userId", "userId is required for this event type"));
            }
        };
    }

    // --- sensitive-data backstop -----------------------------------------

    private void assertNoSensitiveFields(CommerceEvent event, List<ValidationError> errors) {
        // Identity-based visited set: a merchant's payload can contain equal-but-
        // distinct maps legitimately, and equals() would wrongly prune those.
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        walk(event.properties(), "properties", 0, visited, errors);
    }

    private void walk(Object value, String path, int depth, Set<Object> visited, List<ValidationError> errors) {
        if (value == null || depth > MAX_WALK_DEPTH) return;
        if (value instanceof Map<?, ?> map) {
            if (!visited.add(map)) return;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (FORBIDDEN_FIELDS.contains(normalise(key))) {
                    errors.add(new ValidationError(childPath,
                            "\"" + key + "\" looks like sensitive data and must never be tracked"));
                }
                walk(entry.getValue(), childPath, depth + 1, visited, errors);
            }
        } else if (value instanceof Collection<?> collection) {
            if (!visited.add(collection)) return;
            int index = 0;
            for (Object entry : collection) {
                walk(entry, path + "[" + index++ + "]", depth + 1, visited, errors);
            }
        }
    }

    private static String normalise(String key) {
        return NON_ALPHANUMERIC.matcher(key.toLowerCase()).replaceAll("");
    }

    private static CommerceData commerce(CommerceEvent event) {
        return event.commerce() == null ? CommerceData.empty() : event.commerce();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static Set<String> sensitiveFieldNames() {
        return FORBIDDEN_FIELDS;
    }
}
