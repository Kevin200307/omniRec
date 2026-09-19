import { collectContext } from "../context/contextCollector";
import { DwellTimeTracker } from "../dwell/dwellTimeTracker";
import { SCHEMA_VERSION, type CommerceData, type CommerceEvent, type EventType } from "../events/types";
import { IdentityManager, type IdentitySnapshot } from "../identity/identityManager";
import { uuid } from "../storage/storage";
import { Batcher } from "../transport/batcher";
import { OfflineBuffer } from "../transport/offlineBuffer";
import { Transport } from "../transport/transport";
import { EventValidator } from "../validation/validator";
import { CartTracker } from "../trackers/cartTracker";
import { CategoryTracker, ProductListTracker } from "../trackers/catalogTrackers";
import { CheckoutTracker } from "../trackers/checkoutTracker";
import { HomePageTracker, PageTracker } from "../trackers/pageTracker";
import { ProductTracker } from "../trackers/productTracker";
import { PurchaseTracker } from "../trackers/purchaseTracker";
import { RecommendationTracker } from "../trackers/recommendationTracker";
import { SearchTracker } from "../trackers/searchTracker";
import { SessionTracker } from "../trackers/sessionTracker";
import { UserTracker } from "../trackers/userTracker";
import { resolveConfig, type CommerceConfig, type ResolvedConfig } from "./config";
import type { EmitOptions, EventEmitter } from "./emitter";

export interface IdentifyInput {
  userId: string;
  traits?: Record<string, unknown>;
}

/**
 * The facade a merchant holds. It owns identity, context, validation, batching
 * and transport, and exposes behaviour through dedicated trackers rather than
 * one god-object `track()` method.
 *
 *     commerce.product.viewed({ productId: "p123" });
 *     commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 2 });
 *     commerce.user.loggedIn({ userId: "customer_123" });
 *
 * `track()` is still exposed as an escape hatch for anything the trackers don't
 * cover, but reaching for it usually means a tracker method is missing.
 */
export class CommerceClient implements EventEmitter {
  readonly session: SessionTracker;
  readonly page: PageTracker;
  readonly home: HomePageTracker;
  readonly search: SearchTracker;
  readonly productList: ProductListTracker;
  readonly category: CategoryTracker;
  readonly product: ProductTracker;
  readonly cart: CartTracker;
  readonly checkout: CheckoutTracker;
  readonly purchase: PurchaseTracker;
  readonly recommendation: RecommendationTracker;
  readonly user: UserTracker;

  private readonly config: ResolvedConfig;
  private readonly identity: IdentityManager;
  private readonly validator: EventValidator;
  private readonly batcher: Batcher;
  private readonly dwell?: DwellTimeTracker;
  private destroyed = false;

  constructor(config: CommerceConfig) {
    this.config = resolveConfig(config);
    this.validator = new EventValidator();

    this.identity = new IdentityManager({ sessionTimeoutMs: this.config.sessionTimeoutMs });

    const transport = new Transport({
      endpoint: this.config.endpoint,
      apiKey: this.config.apiKey,
      tenantId: this.config.tenantId,
      maxRetries: this.config.maxRetries,
      fetchImpl: this.config.fetchImpl,
    });

    this.batcher = new Batcher(transport, {
      maxBatchSize: this.config.maxBatchSize,
      maxWaitMs: this.config.maxWaitMs,
      maxEventAgeMs: this.config.maxEventAgeMs,
      offlineBuffer: new OfflineBuffer({ maxEvents: this.config.maxOfflineEvents }),
      onDrop: (events, reason) =>
        this.config.onError(new Error(`dropped ${events.length} event(s) (${reason})`)),
    });

    if (this.config.autoTrackDwellTime) {
      // The follow-up is an engagement update, not a second view: it carries
      // viewEventId so interaction-counting destinations can skip it.
      this.dwell = new DwellTimeTracker((productId, dwellTimeMs, commerce, viewEventId) => {
        this.emit(
          "product_viewed",
          { ...commerce, productId },
          viewEventId ? { dwellTimeMs, viewEventId } : { dwellTimeMs }
        );
      });
    }

    this.session = new SessionTracker(this);
    // A page view is a navigation: it ends any product dwell in progress.
    const endDwell = () => this.dwell?.flush();
    this.page = new PageTracker(this, endDwell);
    this.home = new HomePageTracker(this, endDwell);
    this.search = new SearchTracker(this);
    this.productList = new ProductListTracker(this);
    this.category = new CategoryTracker(this);
    this.product = new ProductTracker(this, this.dwell);
    this.cart = new CartTracker(this);
    this.checkout = new CheckoutTracker(this);
    this.purchase = new PurchaseTracker(this);
    this.recommendation = new RecommendationTracker(this);
    this.user = new UserTracker(this, {
      identify: (userId, traits) => this.identify({ userId, traits }),
      logout: () => this.logout(),
    });

    if (this.config.autoTrackSessions) {
      // Subscribe before touching identity so the very first session — created
      // lazily on the first read — still produces session_started.
      this.identity.onSessionStart(() => this.emit("session_started"));
      this.identity.current();
    }
  }

  /**
   * Associates the current anonymous visitor with a merchant user id and emits
   * an `identify` event so the server can record the link. Calling it again
   * with the same userId is a no-op, so it's safe to call on every page load.
   *
   * This does not rewrite past events: they keep the anonymousId they were
   * captured with, and the server resolves history through the link.
   */
  identify(input: IdentifyInput | string): void {
    const userId = typeof input === "string" ? input : input.userId;
    const traits = typeof input === "string" ? undefined : input.traits;
    if (this.identity.identify(userId)) {
      this.emit("identify", {}, traits ? { traits } : {});
    }
  }

  /** Clears the authenticated identity, keeps the anonymousId, rotates the session. */
  logout(): void {
    this.dwell?.flush();
    this.identity.logout();
  }

  /** Current identity, for debugging and for server-rendered pages that need it. */
  getIdentity(): IdentitySnapshot {
    return this.identity.peek();
  }

  /**
   * Escape hatch for event types the trackers don't cover. Prefer a tracker —
   * they exist so that argument shapes stay consistent across a codebase.
   */
  track(eventType: EventType, commerce: CommerceData = {}, properties: Record<string, unknown> = {}): void {
    this.emit(eventType, commerce, properties);
  }

  emit(
    eventType: EventType,
    commerce: CommerceData = {},
    properties: Record<string, unknown> = {},
    options: EmitOptions = {}
  ): string | undefined {
    if (this.destroyed) {
      this.config.onError(new Error(`ignored "${eventType}" — client has been destroyed`));
      return undefined;
    }

    const identity = this.identity.current();
    const event: CommerceEvent = {
      eventId: options.eventId ?? uuid(),
      eventType,
      schemaVersion: SCHEMA_VERSION,
      timestamp: new Date().toISOString(),
      identity,
      context: collectContext(),
      commerce,
      properties,
    };

    if (this.config.validateEvents) {
      const result = this.validator.validate(event);
      if (!result.valid) {
        const detail = result.errors.map((e) => `${e.field}: ${e.message}`).join("; ");
        this.config.onError(new Error(`invalid "${eventType}" event not sent — ${detail}`));
        return undefined;
      }
    }

    if (this.config.debug) {
      console.debug(`[omnirec] ${eventType}`, event);
    }
    this.batcher.enqueue(event);
    return event.eventId;
  }

  /**
   * Forces an immediate send. Resolves once the batch has been delivered or
   * given up on. Deliberately does not end a dwell measurement in progress —
   * flushing is about transport, not about the shopper leaving the product.
   */
  async flush(): Promise<void> {
    await this.batcher.flush();
  }

  /** Number of events buffered in memory plus on disk. */
  pending(): number {
    return this.batcher.pending();
  }

  /** Flushes and detaches every listener. Call on SPA teardown / hot reload. */
  destroy(): void {
    if (this.destroyed) return;
    this.dwell?.destroy();
    this.batcher.destroy();
    this.destroyed = true;
  }
}

export function createCommerceClient(config: CommerceConfig): CommerceClient {
  return new CommerceClient(config);
}
