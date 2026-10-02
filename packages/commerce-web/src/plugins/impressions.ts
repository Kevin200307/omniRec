// SPDX-License-Identifier: Apache-2.0
import type { OmnirecPlugin } from "../core/pipeline";
import { collectFields } from "./fields";

export interface ImpressionOptions {
  /** Share of the element that must be visible. Default 0.5. */
  threshold?: number;
  /** How long it must stay visible, in ms. Default 1000. */
  minVisibleMs?: number;
  /** Fraction of impressions to send, 0..1. Default 1 (all). Decided once per element. */
  sampleRate?: number;
  /** Injected in tests. */
  random?: () => number;
}

/** Fired by the autocapture plugin on every page view, so impressions count per page. */
export const PAGEVIEW_EVENT = "omnirec:pageview";

/**
 * Tracks `data-omnirec-impression="<event>"` elements when they are actually
 * seen: at least half visible for a full second, by default. Each element is
 * counted once per page view, so scrolling a carousel back and forth does not
 * inflate impressions. Elements added later are picked up automatically.
 */
export function impressions(options: ImpressionOptions = {}): OmnirecPlugin {
  const threshold = options.threshold ?? 0.5;
  const minVisibleMs = options.minVisibleMs ?? 1000;
  const sampleRate = options.sampleRate ?? 1;
  const random = options.random ?? Math.random;

  return {
    name: "impressions",
    setup(host) {
      if (typeof document === "undefined" || typeof IntersectionObserver === "undefined") return;

      const timers = new Map<Element, ReturnType<typeof setTimeout>>();
      const sampled = new WeakMap<Element, boolean>();
      let seen = new Set<string>();

      const keyOf = (element: Element, name: string, data: unknown) => `${name}|${JSON.stringify(data)}`;

      const fire = (element: Element) => {
        timers.delete(element);
        const name = element.getAttribute("data-omnirec-impression");
        if (!name || !element.isConnected) return;
        const fields = collectFields(element);
        const key = keyOf(element, name, fields.data);
        if (seen.has(key)) return;
        seen.add(key);
        host.track(name, fields.data, { properties: fields.properties });
      };

      const observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const element = entry.target;
            if (entry.isIntersecting && entry.intersectionRatio >= threshold) {
              if (!timers.has(element)) timers.set(element, setTimeout(() => fire(element), minVisibleMs));
            } else {
              const timer = timers.get(element);
              if (timer !== undefined) {
                clearTimeout(timer);
                timers.delete(element);
              }
            }
          }
        },
        { threshold: [0, threshold] }
      );

      const watch = (element: Element) => {
        if (!sampled.has(element)) sampled.set(element, random() < sampleRate);
        if (sampled.get(element)) observer.observe(element);
      };
      const scan = (root: ParentNode) => {
        if (root instanceof Element && root.hasAttribute("data-omnirec-impression")) watch(root);
        root.querySelectorAll("[data-omnirec-impression]").forEach(watch);
      };
      scan(document);

      const mutations =
        typeof MutationObserver === "undefined"
          ? undefined
          : new MutationObserver((records) => {
              for (const record of records) {
                record.addedNodes.forEach((node) => {
                  if (node instanceof Element) scan(node);
                });
                record.removedNodes.forEach((node) => {
                  if (node instanceof Element) {
                    observer.unobserve(node);
                    const timer = timers.get(node);
                    if (timer !== undefined) clearTimeout(timer);
                    timers.delete(node);
                  }
                });
              }
            });
      mutations?.observe(document.documentElement, { childList: true, subtree: true });

      const reset = () => {
        seen = new Set();
      };
      window.addEventListener(PAGEVIEW_EVENT, reset);

      return () => {
        observer.disconnect();
        mutations?.disconnect();
        for (const timer of timers.values()) clearTimeout(timer);
        timers.clear();
        window.removeEventListener(PAGEVIEW_EVENT, reset);
      };
    },
  };
}
