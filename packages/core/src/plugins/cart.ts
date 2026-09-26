// SPDX-License-Identifier: Apache-2.0
import type { Plugin, PluginContext } from "../types";

const INACTIVITY_MS = 15 * 60 * 1000; // 15 min with no cart mutation → abandoned

/**
 * There's no generic DOM signal for "added to cart" the way scroll/visibility
 * are generic browser signals — cart mutations are app-specific business
 * logic. So this plugin listens for two CustomEvents the host app dispatches
 * (or that useOmnirec()'s trackCartAdd/trackCartRemove helpers dispatch for
 * them): "omnirec:cart:add" and "omnirec:cart:remove", each with
 * detail: { productId, quantity? }.
 *
 * It tracks CART_ADD/CART_REMOVE immediately, and fires CART_ABANDONED once
 * — via the unload-safe beacon path — if the cart is non-empty and either
 * INACTIVITY_MS elapses or the tab closes/hides.
 */
export function cartPlugin(): Plugin {
  const cartItems = new Map<string, number>();
  let inactivityTimer: ReturnType<typeof setTimeout> | null = null;
  let abandonedFired = false;

  function resetInactivityTimer(track: PluginContext["track"]): void {
    if (inactivityTimer) clearTimeout(inactivityTimer);
    if (cartItems.size === 0) return;
    inactivityTimer = setTimeout(() => fireAbandoned(track), INACTIVITY_MS);
  }

  function fireAbandoned(track: PluginContext["track"]): void {
    if (abandonedFired || cartItems.size === 0) return;
    abandonedFired = true;
    track("CART_ABANDONED", "IMPLICIT", {
      items: Array.from(cartItems.entries()).map(([productId, quantity]) => ({ productId, quantity })),
    });
  }

  let onAdd: ((e: Event) => void) | null = null;
  let onRemove: ((e: Event) => void) | null = null;
  let onVisibilityChange: (() => void) | null = null;

  return {
    name: "cart",
    install(ctx: PluginContext) {
      if (typeof window === "undefined") return;

      onAdd = (e: Event) => {
        const { productId, quantity = 1 } = (e as CustomEvent).detail ?? {};
        if (!productId) return;
        cartItems.set(productId, (cartItems.get(productId) ?? 0) + quantity);
        abandonedFired = false;
        ctx.track("CART_ADD", "IMPLICIT", { productId, quantity });
        resetInactivityTimer(ctx.track);
      };

      onRemove = (e: Event) => {
        const { productId } = (e as CustomEvent).detail ?? {};
        if (!productId) return;
        cartItems.delete(productId);
        ctx.track("CART_REMOVE", "IMPLICIT", { productId });
        resetInactivityTimer(ctx.track);
      };

      onVisibilityChange = () => {
        if (document.visibilityState === "hidden") fireAbandoned(ctx.track);
      };

      window.addEventListener("omnirec:cart:add", onAdd);
      window.addEventListener("omnirec:cart:remove", onRemove);
      document.addEventListener("visibilitychange", onVisibilityChange);
    },
    uninstall() {
      if (typeof window === "undefined") return;
      if (onAdd) window.removeEventListener("omnirec:cart:add", onAdd);
      if (onRemove) window.removeEventListener("omnirec:cart:remove", onRemove);
      if (onVisibilityChange) document.removeEventListener("visibilitychange", onVisibilityChange);
      if (inactivityTimer) clearTimeout(inactivityTimer);
    },
  };
}
