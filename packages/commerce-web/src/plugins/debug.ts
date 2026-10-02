// SPDX-License-Identifier: Apache-2.0
import type { OmnirecPlugin } from "../core/pipeline";
import { EVENT_ALIASES, EVENT_NAMES, EVENT_RULES } from "../events/generated/catalog";
import { EventValidator } from "../validation/validator";

export interface DebugOptions {
  /** Where output goes. Default `console`. */
  logger?: Pick<Console, "log" | "warn" | "groupCollapsed" | "groupEnd">;
  /** Warn when the same event for the same product repeats within this many ms. Default 500. */
  duplicateWindowMs?: number;
  /** Names to suggest from, for example your plan's custom events. Defaults to the standard catalog. */
  knownNames?: readonly string[];
  now?: () => number;
}

/** Levenshtein distance, stopping early once it exceeds `max`. */
export function distance(a: string, b: string, max = 2): number {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    let rowMin = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost);
      rowMin = Math.min(rowMin, current[j]);
    }
    if (rowMin > max) return max + 1;
    previous = current;
  }
  return previous[b.length];
}

/** The closest known name within two edits, or undefined. */
export function suggest(name: string, known: readonly string[]): string | undefined {
  let best: string | undefined;
  let bestDistance = 3;
  for (const candidate of known) {
    const d = distance(name, candidate);
    if (d < bestDistance) {
      best = candidate;
      bestDistance = d;
    }
  }
  return best;
}

/**
 * Development aid. Logs every event that is sent, suggests the intended name
 * for unknown ones (`add_to_card` → `add_to_cart`), and warns about likely
 * double tracking — the same event for the same product twice within half a
 * second, usually an HTML attribute and a `track()` call on the same click.
 * Leave it out of production builds.
 */
export function debug(options: DebugOptions = {}): OmnirecPlugin[] {
  const logger = options.logger ?? console;
  const windowMs = options.duplicateWindowMs ?? 500;
  const known = new Set<string>(options.knownNames ?? [...EVENT_NAMES, ...Object.keys(EVENT_ALIASES)]);
  const now = options.now ?? (() => Date.now());
  const recent = new Map<string, number>();
  // The core checks required fields only; debug adds the catalog's constraints
  // so a value the server will reject shows up in the console during development.
  const fullCheck = new EventValidator(EVENT_RULES);

  const inspect: OmnirecPlugin = {
    name: "debug:inspect",
    phase: "before-validate",
    middleware(event, next) {
      if (!known.has(event.event)) {
        const hint = suggest(event.event, [...known]);
        logger.warn(
          `[omnirec] "${event.event}" is not a standard event${hint ? `. Did you mean "${hint}"?` : ". If it is a custom event, add it to your tracking plan."}`
        );
      }
      const result = fullCheck.validate(event);
      if (!result.valid) {
        logger.warn(
          `[omnirec] "${event.event}" will be rejected by the server: ` +
            result.errors.map((e) => `${e.field}: ${e.message}`).join("; ")
        );
      }
      const productId = (event.data as { product?: { id?: unknown } }).product?.id;
      const key = `${event.event}|${typeof productId === "string" ? productId : ""}`;
      const at = now();
      const last = recent.get(key);
      if (last !== undefined && at - last < windowMs) {
        logger.warn(
          `[omnirec] "${event.event}" sent twice within ${windowMs}ms${productId ? ` for product ${String(productId)}` : ""}. ` +
            "Is it tracked both by an HTML attribute and by track()?"
        );
      }
      recent.set(key, at);
      next(event);
    },
  };

  const log: OmnirecPlugin = {
    name: "debug:log",
    middleware(event, next) {
      logger.groupCollapsed(`[omnirec] ${event.event}`);
      logger.log(event);
      logger.groupEnd();
      next(event);
    },
  };

  return [inspect, log];
}
