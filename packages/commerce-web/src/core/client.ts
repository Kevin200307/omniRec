// SPDX-License-Identifier: Apache-2.0
import { DwellTimeTracker } from "../dwell/dwellTimeTracker";
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
import type { CommerceConfig } from "./config";
import { OmnirecClient } from "./omnirec";

export type { IdentifyInput } from "./omnirec";

/**
 * {@link OmnirecClient} plus the v1 per-event helper methods and dwell-time
 * measurement:
 *
 *     commerce.product.viewed({ productId: "p123" });
 *     commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 2 });
 *
 * The helpers take the v1 flat payload and convert it to v2 blocks. They stay
 * for existing integrations. New code calls `track()` with v2 data, which is
 * fully typed from the catalog and keeps the bundle smaller.
 *
 * @deprecated use `createOmnirec()` and `track()`; dwell time moves to the
 * autocapture plugin.
 */
export class CommerceClient extends OmnirecClient {
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

  private readonly dwell?: DwellTimeTracker;

  constructor(config: CommerceConfig) {
    super(config);

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
  }

  /** Ends any dwell measurement in progress, then rotates the session. */
  override logout(): void {
    this.dwell?.flush();
    super.logout();
  }

  override destroy(): void {
    if (this.destroyed) return;
    this.dwell?.destroy();
    super.destroy();
  }
}

/** @deprecated use `createOmnirec()`. */
export function createCommerceClient(config: CommerceConfig): CommerceClient {
  return new CommerceClient(config);
}
