export { CommerceClient, createCommerceClient, type IdentifyInput } from "./core/client";
export { resolveConfig, type CommerceConfig, type ResolvedConfig } from "./core/config";
export { businessEventId, compact, type EmitOptions, type EventEmitter } from "./core/emitter";

export {
  EVENT_TYPES,
  SCHEMA_VERSION,
  type CartEventType,
  type CheckoutEventType,
  type CommerceData,
  type CommerceEvent,
  type CommerceItem,
  type DeviceType,
  type DiscoveryEventType,
  type EventContext,
  type EventIdentity,
  type EventType,
  type IdentityEventType,
  type Platform,
  type ProductInteractionEventType,
  type PurchaseEventType,
  type RecommendationEventType,
  type SessionEventType,
  type UserEventType,
} from "./events/types";

export {
  DEFAULT_SESSION_TIMEOUT_MS,
  IdentityManager,
  type IdentityManagerOptions,
  type IdentitySnapshot,
} from "./identity/identityManager";

export { collectContext, detectDevice } from "./context/contextCollector";
export { sanitizeUrl, SENSITIVE_URL_PARAMS } from "./context/sanitizeUrl";
export { DwellTimeTracker, type DwellTimeOptions } from "./dwell/dwellTimeTracker";

export {
  EventValidator,
  SENSITIVE_FIELD_NAMES,
  type ValidationError,
  type ValidationResult,
} from "./validation/validator";

export { Transport, classify, type SendOutcome, type TransportOptions } from "./transport/transport";
export { Batcher, type BatcherOptions } from "./transport/batcher";
export { OfflineBuffer, type OfflineBufferOptions } from "./transport/offlineBuffer";
export { CookieStore, LocalStore, MemoryStore, uuid, type KeyValueStore } from "./storage/storage";

export { SessionTracker } from "./trackers/sessionTracker";
export { HomePageTracker, PageTracker, type PageViewedInput } from "./trackers/pageTracker";
export {
  SearchTracker,
  type SearchPerformedInput,
  type SearchResultClickedInput,
} from "./trackers/searchTracker";
export {
  CategoryTracker,
  ProductListTracker,
  type CategoryViewedInput,
  type ProductListViewedInput,
} from "./trackers/catalogTrackers";
export {
  ProductTracker,
  type ProductClickedInput,
  type ProductReviewSubmittedInput,
  type ProductSharedInput,
  type ProductViewedInput,
} from "./trackers/productTracker";
export {
  CartTracker,
  type CartProductAddedInput,
  type CartProductRemovedInput,
  type CartQuantityUpdatedInput,
  type CartViewedInput,
} from "./trackers/cartTracker";
export {
  CheckoutTracker,
  type CheckoutFailedInput,
  type CheckoutStepInput,
  type PaymentInformationAddedInput,
} from "./trackers/checkoutTracker";
export {
  PurchaseTracker,
  type OrderChangeInput,
  type PurchaseCompletedInput,
  type PurchaseFailedInput,
} from "./trackers/purchaseTracker";
export {
  RecommendationTracker,
  type RecommendationImpressionInput,
  type RecommendationInteractionInput,
} from "./trackers/recommendationTracker";
export { UserTracker, type IdentityActions, type UserInput } from "./trackers/userTracker";
