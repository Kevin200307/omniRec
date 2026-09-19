import { compact, type EventEmitter } from "../core/emitter";

export interface ProductListViewedInput {
  productIds: string[];
  listId?: string;
  categoryId?: string;
  [key: string]: unknown;
}

export interface CategoryViewedInput {
  categoryId: string;
  category?: string;
  productIds?: string[];
  [key: string]: unknown;
}

/** Grid / carousel / search-results impressions: which products were actually shown. */
export class ProductListTracker {
  constructor(private readonly emitter: EventEmitter) {}

  viewed(input: ProductListViewedInput): void {
    const { productIds, listId, categoryId, ...rest } = input;
    this.emitter.emit("product_list_viewed", compact({ productIds, listId, categoryId }), compact(rest));
  }
}

export class CategoryTracker {
  constructor(private readonly emitter: EventEmitter) {}

  viewed(input: CategoryViewedInput): void {
    const { categoryId, category, productIds, ...rest } = input;
    this.emitter.emit("category_viewed", compact({ categoryId, category, productIds }), compact(rest));
  }
}
