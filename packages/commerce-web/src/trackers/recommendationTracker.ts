// SPDX-License-Identifier: Apache-2.0
import { compact, type EventEmitter } from "../core/emitter";

export interface RecommendationImpressionInput {
  recommendationId: string;
  /**
   * Who served this list: "amazon-personalize", "google-retail", or your own
   * engine's name. An adapter forwards `recommendationId` as attribution only
   * to the provider that issued it; any other provider would reject it.
   */
  recommendationProvider?: string;
  productIds: string[];
  /** Where the widget was shown, e.g. "homepage", "pdp_related". */
  source?: string;
  [key: string]: unknown;
}

export interface RecommendationInteractionInput {
  recommendationId: string;
  recommendationProvider?: string;
  productId: string;
  position?: number;
  source?: string;
  [key: string]: unknown;
}

/**
 * Closes the loop: which recommendations were *shown* (impression) versus which
 * were acted on. Without impressions a model can't distinguish "not clicked"
 * from "never displayed", which is exactly the difference that makes
 * click-through learnable.
 *
 * `recommendationId` identifies one rendered list. When a provider served it,
 * pass that provider's id (Personalize's recommendationId, Retail's
 * attributionToken) and name the provider in `recommendationProvider`; the
 * matching adapter then forwards it as attribution and every other adapter
 * ignores it. For your own engine, any id works and nothing is forwarded.
 */
export class RecommendationTracker {
  constructor(private readonly emitter: EventEmitter) {}

  impression(input: RecommendationImpressionInput): void {
    const { recommendationId, recommendationProvider, productIds, source, ...rest } = input;
    this.emitter.emit(
      "recommendation_impression",
      compact({ recommendationId, recommendationProvider, productIds }),
      compact({ source, ...rest })
    );
  }

  clicked(input: RecommendationInteractionInput): void {
    this.interaction("recommendation_clicked", input);
  }

  addedToCart(input: RecommendationInteractionInput): void {
    this.interaction("recommendation_added_to_cart", input);
  }

  purchased(input: RecommendationInteractionInput): void {
    this.interaction("recommendation_purchased", input);
  }

  private interaction(
    eventType: "recommendation_clicked" | "recommendation_added_to_cart" | "recommendation_purchased",
    input: RecommendationInteractionInput
  ): void {
    const { recommendationId, recommendationProvider, productId, position, source, ...rest } = input;
    this.emitter.emit(eventType, compact({ recommendationId, recommendationProvider, productId }), compact({ position, source, ...rest }));
  }
}
