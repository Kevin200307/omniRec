// SPDX-License-Identifier: Apache-2.0
import type { OmnirecPlugin } from "../core/pipeline";
import type { CommerceEvent } from "../events/types";
import { EVENT_ALIASES, MARKETING_EVENTS } from "../events/generated/catalog";

export type ConsentState = "unknown" | "granted" | "denied";
export type ConsentCategory = "analytics" | "marketing";

export interface ConsentOptions {
  /** Starting state per category. Unset categories are "unknown". */
  initial?: Partial<Record<ConsentCategory, ConsentState>>;
  /** Which category an event needs. Default: acquisition and messaging events are marketing, everything else analytics. */
  categoryOf?: (event: CommerceEvent) => ConsentCategory;
  /** Events held while consent is unknown. Oldest are dropped past this. Default 100. */
  maxQueued?: number;
  /** Remember choices in localStorage. Default true. */
  persist?: boolean;
}

export interface ConsentPlugin extends OmnirecPlugin {
  /** Records the visitor's choice. Held events are sent or dropped accordingly. */
  set(choices: Partial<Record<ConsentCategory, boolean>>): void;
  /** Current state per category. */
  state(): Record<ConsentCategory, ConsentState>;
}

const STORAGE_KEY = "omnirec_consent";
const MARKETING = new Set<string>(MARKETING_EVENTS);

function defaultCategory(event: CommerceEvent): ConsentCategory {
  const name = EVENT_ALIASES[event.event] ?? event.event;
  return MARKETING.has(name) ? "marketing" : "analytics";
}

/**
 * Holds events until the visitor decides, then sends or drops them. Identity
 * events (`identify`) follow the analytics category.
 *
 *     const cmp = consent();
 *     createOmnirec({ endpoint: "/omnirec", plugins: [cmp] });
 *     cmp.set({ analytics: true, marketing: false });
 */
export function consent(options: ConsentOptions = {}): ConsentPlugin {
  const categoryOf = options.categoryOf ?? defaultCategory;
  const maxQueued = options.maxQueued ?? 100;
  const persist = options.persist ?? true;

  const states: Record<ConsentCategory, ConsentState> = { analytics: "unknown", marketing: "unknown" };
  Object.assign(states, options.initial ?? {});
  if (persist) {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
      if (saved && typeof saved === "object") Object.assign(states, saved);
    } catch {
      // no stored choice
    }
  }

  const queue: Array<{ event: CommerceEvent; category: ConsentCategory; next: (event: CommerceEvent) => void }> = [];

  return {
    name: "consent",
    phase: "before-validate",
    middleware(event, next) {
      const category = categoryOf(event);
      const state = states[category];
      if (state === "granted") {
        next(event);
      } else if (state === "unknown") {
        queue.push({ event, category, next });
        if (queue.length > maxQueued) queue.shift();
      }
      // denied: dropped
    },
    set(choices) {
      for (const [category, allowed] of Object.entries(choices) as Array<[ConsentCategory, boolean | undefined]>) {
        if (allowed !== undefined) states[category] = allowed ? "granted" : "denied";
      }
      if (persist) {
        try {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(states));
        } catch {
          // storage blocked: the choice holds for this page
        }
      }
      for (let i = 0; i < queue.length; ) {
        const held = queue[i];
        const state = states[held.category];
        if (state === "unknown") {
          i++;
          continue;
        }
        queue.splice(i, 1);
        if (state === "granted") held.next(held.event);
      }
    },
    state() {
      return { ...states };
    },
  };
}
