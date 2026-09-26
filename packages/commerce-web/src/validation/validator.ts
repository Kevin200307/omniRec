// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent, EventType } from "../events/types";
import { EVENT_TYPES } from "../events/types";

export interface ValidationError {
  field: string;
  message: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

/**
 * Field names that must never appear in an event, at any depth, under any
 * casing. This is a hard backstop, not advice: a merchant who accidentally
 * spreads a whole checkout form into `properties` should have the event
 * rejected rather than have a PAN end up in a provider's training data.
 *
 * Matching is on a normalised name (lowercased, separators stripped), so
 * `card_number`, `cardNumber`, and `CardNumber` are all the same key.
 */
const FORBIDDEN_FIELDS = [
  "cardnumber",
  "cardno",
  "pan",
  "cvv",
  "cvc",
  "cvv2",
  "securitycode",
  "cardsecuritycode",
  "expirymonth",
  "expiryyear",
  "cardexpiry",
  "password",
  "passwd",
  "pin",
  "ssn",
  "socialsecuritynumber",
  "accesstoken",
  "refreshtoken",
  "apikey",
  "apisecret",
  "secretkey",
  "privatekey",
  "authorization",
  "creditcard",
  "iban",
];

const ISO_4217 = /^[A-Z]{3}$/;

type Rule = (event: CommerceEvent, errors: ValidationError[]) => void;

const requireProductId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.productId)) {
    errors.push({ field: "commerce.productId", message: "productId is required" });
  }
};

const requireCartId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.cartId)) {
    errors.push({ field: "commerce.cartId", message: "cartId is required" });
  }
};

const requireOrderId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.orderId)) {
    errors.push({ field: "commerce.orderId", message: "orderId is required" });
  }
};

const requirePositiveQuantity: Rule = (event, errors) => {
  const { quantity } = event.commerce;
  if (quantity === undefined || quantity === null) {
    errors.push({ field: "commerce.quantity", message: "quantity is required" });
    return;
  }
  if (typeof quantity !== "number" || !Number.isFinite(quantity)) {
    errors.push({ field: "commerce.quantity", message: "quantity must be a number" });
    return;
  }
  if (quantity <= 0) {
    errors.push({ field: "commerce.quantity", message: "quantity must be greater than 0" });
  }
};

const requireCurrency: Rule = (event, errors) => {
  const { currency } = event.commerce;
  if (!isNonEmptyString(currency)) {
    errors.push({ field: "commerce.currency", message: "currency is required" });
    return;
  }
  if (!ISO_4217.test(currency)) {
    errors.push({ field: "commerce.currency", message: "currency must be a 3-letter ISO 4217 code" });
  }
};

const requireTotal: Rule = (event, errors) => {
  const { total } = event.commerce;
  if (total === undefined || total === null) {
    errors.push({ field: "commerce.total", message: "total is required" });
    return;
  }
  if (typeof total !== "number" || !Number.isFinite(total) || total < 0) {
    errors.push({ field: "commerce.total", message: "total must be a non-negative number" });
  }
};

const requireItems: Rule = (event, errors) => {
  const { items } = event.commerce;
  if (!Array.isArray(items) || items.length === 0) {
    errors.push({ field: "commerce.items", message: "items is required and must be non-empty" });
    return;
  }
  items.forEach((item, index) => {
    if (!isNonEmptyString(item?.productId)) {
      errors.push({ field: `commerce.items[${index}].productId`, message: "productId is required" });
    }
    if (item?.quantity !== undefined && (typeof item.quantity !== "number" || item.quantity <= 0)) {
      errors.push({ field: `commerce.items[${index}].quantity`, message: "quantity must be greater than 0" });
    }
  });
};

const requireSearchQuery: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.searchQuery)) {
    errors.push({ field: "commerce.searchQuery", message: "query is required" });
  }
};

const requireCategoryId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.categoryId)) {
    errors.push({ field: "commerce.categoryId", message: "categoryId is required" });
  }
};

const requireRecommendationId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.commerce.recommendationId)) {
    errors.push({ field: "commerce.recommendationId", message: "recommendationId is required" });
  }
};

const requireProductIds: Rule = (event, errors) => {
  const { productIds } = event.commerce;
  if (!Array.isArray(productIds) || productIds.length === 0) {
    errors.push({ field: "commerce.productIds", message: "productIds is required and must be non-empty" });
  }
};

const requireUserId: Rule = (event, errors) => {
  if (!isNonEmptyString(event.identity.userId)) {
    errors.push({ field: "identity.userId", message: "userId is required for this event type" });
  }
};

/**
 * Per-event-type rules. An event type absent from this map has no
 * type-specific requirements beyond the universal ones — that is intentional,
 * not an oversight: session_started and page_viewed genuinely need nothing
 * more than identity and context.
 */
const RULES: Partial<Record<EventType, Rule[]>> = {
  search_performed: [requireSearchQuery],
  search_result_clicked: [requireSearchQuery, requireProductId],
  product_list_viewed: [requireProductIds],
  category_viewed: [requireCategoryId],
  product_viewed: [requireProductId],
  product_clicked: [requireProductId],
  product_wishlisted: [requireProductId],
  product_shared: [requireProductId],
  product_compared: [requireProductId],
  product_review_viewed: [requireProductId],
  product_review_submitted: [requireProductId],
  cart_viewed: [requireCartId],
  product_added_to_cart: [requireProductId, requirePositiveQuantity],
  product_removed_from_cart: [requireProductId],
  cart_quantity_updated: [requireProductId],
  cart_abandoned: [requireCartId],
  checkout_started: [requireCartId],
  shipping_information_added: [requireCartId],
  payment_information_added: [requireCartId],
  checkout_completed: [requireCartId],
  checkout_failed: [requireCartId],
  purchase_completed: [requireOrderId, requireItems, requireCurrency, requireTotal],
  purchase_failed: [requireOrderId],
  order_cancelled: [requireOrderId],
  order_refunded: [requireOrderId],
  recommendation_impression: [requireRecommendationId, requireProductIds],
  recommendation_clicked: [requireRecommendationId, requireProductId],
  recommendation_added_to_cart: [requireRecommendationId, requireProductId],
  recommendation_purchased: [requireRecommendationId, requireProductId],
  user_registered: [requireUserId],
  user_logged_in: [requireUserId],
  user_profile_updated: [requireUserId],
  identify: [requireUserId],
};

/**
 * Validates a canonical event. Runs on both sides of the wire: the SDK calls it
 * before enqueueing so a developer sees the mistake in their console, and the
 * Event API calls the Java equivalent because a client-side check is a
 * convenience, never a guarantee.
 */
export class EventValidator {
  validate(event: CommerceEvent): ValidationResult {
    const errors: ValidationError[] = [];

    this.validateUniversal(event, errors);
    for (const rule of RULES[event.eventType] ?? []) {
      rule(event, errors);
    }
    this.assertNoSensitiveFields(event, errors);

    return { valid: errors.length === 0, errors };
  }

  private validateUniversal(event: CommerceEvent, errors: ValidationError[]): void {
    if (!isNonEmptyString(event.eventId)) {
      errors.push({ field: "eventId", message: "eventId is required" });
    }
    if (!EVENT_TYPES.includes(event.eventType)) {
      errors.push({ field: "eventType", message: `unknown eventType "${event.eventType}"` });
    }
    if (!isNonEmptyString(event.schemaVersion)) {
      errors.push({ field: "schemaVersion", message: "schemaVersion is required" });
    }
    if (!isValidTimestamp(event.timestamp)) {
      errors.push({ field: "timestamp", message: "timestamp must be a valid ISO-8601 date-time" });
    }
    if (!isNonEmptyString(event.identity?.anonymousId)) {
      errors.push({ field: "identity.anonymousId", message: "anonymousId is required" });
    }
    if (!isNonEmptyString(event.identity?.sessionId)) {
      errors.push({ field: "identity.sessionId", message: "sessionId is required" });
    }
  }

  /**
   * Walks the whole event. Cost is bounded by payload size, which the API caps
   * anyway, and the alternative — checking only the top level — would miss the
   * realistic accident of nesting a form object one level down.
   */
  private assertNoSensitiveFields(event: CommerceEvent, errors: ValidationError[]): void {
    const seen = new Set<unknown>();

    const walk = (value: unknown, path: string, depth: number): void => {
      if (value === null || typeof value !== "object" || depth > 12) return;
      if (seen.has(value)) return;
      seen.add(value);

      if (Array.isArray(value)) {
        value.forEach((entry, index) => walk(entry, `${path}[${index}]`, depth + 1));
        return;
      }

      for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
        const childPath = path ? `${path}.${key}` : key;
        if (FORBIDDEN_FIELDS.includes(normaliseKey(key))) {
          errors.push({
            field: childPath,
            message: `"${key}" looks like sensitive data and must never be tracked`,
          });
        }
        walk(entry, childPath, depth + 1);
      }
    };

    walk(event.commerce, "commerce", 0);
    walk(event.properties, "properties", 0);
  }
}

function normaliseKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isValidTimestamp(value: unknown): boolean {
  if (typeof value !== "string" || value.trim().length === 0) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed);
}

export const SENSITIVE_FIELD_NAMES = FORBIDDEN_FIELDS;
