/**
 * Mirrors schema/canonical-event.schema.json. Keep in sync manually until
 * the schema→types codegen step lands — CI's contract test catches drift.
 */

export type EventCategory = "EXPLICIT" | "IMPLICIT" | "CONTEXTUAL";

export type EventType =
  | "PRODUCT_VIEWED"
  | "PRODUCT_CLICKED"
  | "PRODUCT_DWELL"
  | "SCROLL_DEPTH"
  | "CART_ADD"
  | "CART_REMOVE"
  | "CART_ABANDONED"
  | "WISHLIST_ADD"
  | "SEARCH_QUERY"
  | "REVIEW_SUBMITTED"
  | "PURCHASE_COMPLETED"
  | "PAGE_VIEW";

/** Client only ever proposes a subset — timestamp/geo/device are stamped server-side on ingestion. */
export interface ClientEventContext {
  season?: string | null;
}

export interface OutgoingEvent {
  eventId: string;
  tenantId: string;
  userId: string | null;
  anonymousId: string;
  sessionId: string;
  eventType: EventType;
  category: EventCategory;
  payload: Record<string, unknown>;
  context: ClientEventContext;
  /** Client-side capture time, purely diagnostic — never authoritative. */
  capturedAt: string;
  /** Snapshot of consent state at capture time. Backend drops IMPLICIT/CONTEXTUAL events unless this is "granted" — defense in depth alongside the frontend's own plugin gating. */
  consent: "granted" | "denied" | "unknown";
}

export interface OmnirecConfig {
  tenantId: string;
  endpoint: string;
  plugins?: PluginName[];
  /** Batching: flush after N events or after intervalMs, whichever comes first. */
  batchSize?: number;
  intervalMs?: number;
  /** Consent must be granted before implicit/contextual plugins activate. Explicit calls to track() are never gated. */
  requireConsent?: boolean;
}

export type PluginName = "dwellTime" | "scrollDepth" | "cart" | "wishlist";

export interface Plugin {
  name: PluginName;
  /** Called once consent (if required) is granted and the tracker is ready. */
  install(ctx: PluginContext): void;
  uninstall?(): void;
}

export interface PluginContext {
  track: (eventType: EventType, category: EventCategory, payload?: Record<string, unknown>) => void;
}
