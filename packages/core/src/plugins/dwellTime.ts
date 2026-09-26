// SPDX-License-Identifier: Apache-2.0
import type { Plugin, PluginContext } from "../types";

/**
 * Tracks per-product dwell time using IntersectionObserver (element is
 * actually on screen) combined with the Page Visibility API (tab is
 * actually focused) — either one alone overcounts: a scrolled-past card
 * that's technically in the DOM, or a backgrounded tab left open overnight.
 *
 * Elements opt in via data-omnirec-product-id, which <ProductImpression>
 * in @omnirec/react-ui sets automatically.
 */
export function dwellTimePlugin(): Plugin {
  let observer: IntersectionObserver | null = null;
  const activeSince = new Map<string, number>();

  function isPageVisible(): boolean {
    return typeof document === "undefined" || document.visibilityState === "visible";
  }

  function startDwell(productId: string): void {
    if (!activeSince.has(productId) && isPageVisible()) {
      activeSince.set(productId, Date.now());
    }
  }

  function stopDwell(productId: string, track: PluginContext["track"]): void {
    const start = activeSince.get(productId);
    if (start === undefined) return;
    activeSince.delete(productId);
    const dwellMs = Date.now() - start;
    if (dwellMs < 250) return; // filters out scroll-through noise
    track("PRODUCT_DWELL", "IMPLICIT", { productId, dwellMs });
  }

  return {
    name: "dwellTime",
    install(ctx: PluginContext) {
      if (typeof window === "undefined" || !("IntersectionObserver" in window)) return;

      observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const productId = (entry.target as HTMLElement).dataset.omnirecProductId;
            if (!productId) continue;
            if (entry.isIntersecting) {
              startDwell(productId);
            } else {
              stopDwell(productId, ctx.track);
            }
          }
        },
        { threshold: 0.5 }
      );

      document.querySelectorAll<HTMLElement>("[data-omnirec-product-id]").forEach((el) => observer!.observe(el));

      // New product cards render after install (infinite scroll, SPA
      // navigation) — a MutationObserver keeps them wired without the
      // host app having to re-register anything.
      const mutationObserver = new MutationObserver(() => {
        document.querySelectorAll<HTMLElement>("[data-omnirec-product-id]").forEach((el) => observer!.observe(el));
      });
      mutationObserver.observe(document.body, { childList: true, subtree: true });

      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") {
          for (const productId of [...activeSince.keys()]) stopDwell(productId, ctx.track);
        }
      });
    },
  };
}
