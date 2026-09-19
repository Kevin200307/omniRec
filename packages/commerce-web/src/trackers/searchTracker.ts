import { compact, type EventEmitter } from "../core/emitter";

export interface SearchPerformedInput {
  query: string;
  resultCount?: number;
  filters?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface SearchResultClickedInput {
  query: string;
  productId: string;
  /** 1-based rank of the clicked result. The strongest relevance signal search produces. */
  position?: number;
  [key: string]: unknown;
}

export class SearchTracker {
  constructor(private readonly emitter: EventEmitter) {}

  performed(input: SearchPerformedInput): void {
    const { query, resultCount, ...rest } = input;
    this.emitter.emit("search_performed", { searchQuery: query }, compact({ resultCount, ...rest }));
  }

  resultClicked(input: SearchResultClickedInput): void {
    const { query, productId, position, ...rest } = input;
    this.emitter.emit(
      "search_result_clicked",
      { searchQuery: query, productId },
      compact({ position, ...rest })
    );
  }
}
