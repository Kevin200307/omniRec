import { useCallback, useContext } from "react";
import type { EventCategory, EventType } from "@omnirec/core";
import { OmnirecContext } from "./OmnirecProvider";

export function useOmnirec() {
  const ctx = useContext(OmnirecContext);
  if (!ctx) {
    throw new Error("useOmnirec() must be called within <OmnirecProvider>.");
  }
  const { tracker, tenantId, endpoint } = ctx;

  const track = useCallback(
    (eventType: EventType, category: EventCategory = "EXPLICIT", payload: Record<string, unknown> = {}) =>
      tracker.track(eventType, category, payload),
    [tracker]
  );

  const identify = useCallback((userId: string) => tracker.identify(userId), [tracker]);
  const grantConsent = useCallback(() => tracker.grantConsent(), [tracker]);
  const denyConsent = useCallback(() => tracker.denyConsent(), [tracker]);

  // Cart mutations go through CustomEvents so @omnirec/core's cart plugin
  // (which has no React dependency) can pick them up — see plugins/cart.ts.
  const trackCartAdd = useCallback((productId: string, quantity = 1) => {
    window.dispatchEvent(new CustomEvent("omnirec:cart:add", { detail: { productId, quantity } }));
  }, []);

  const trackCartRemove = useCallback((productId: string) => {
    window.dispatchEvent(new CustomEvent("omnirec:cart:remove", { detail: { productId } }));
  }, []);

  return { track, identify, grantConsent, denyConsent, trackCartAdd, trackCartRemove, tracker, tenantId, endpoint };
}
