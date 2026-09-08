import type { Plugin, PluginContext } from "../types";

const THRESHOLDS = [25, 50, 75, 100];

export function scrollDepthPlugin(): Plugin {
  let firedThresholds = new Set<number>();
  let handler: (() => void) | null = null;

  function currentDepthPercent(): number {
    const doc = document.documentElement;
    const scrollable = doc.scrollHeight - doc.clientHeight;
    if (scrollable <= 0) return 100;
    return Math.round(((doc.scrollTop || window.scrollY) / scrollable) * 100);
  }

  return {
    name: "scrollDepth",
    install(ctx: PluginContext) {
      if (typeof window === "undefined") return;
      firedThresholds = new Set();

      handler = () => {
        const depth = currentDepthPercent();
        for (const threshold of THRESHOLDS) {
          if (depth >= threshold && !firedThresholds.has(threshold)) {
            firedThresholds.add(threshold);
            ctx.track("SCROLL_DEPTH", "IMPLICIT", { depthPercent: threshold, path: location.pathname });
          }
        }
      };

      window.addEventListener("scroll", handler, { passive: true });

      // Reset per SPA navigation so depth is measured per page, not
      // cumulatively across a whole session.
      let lastPath = location.pathname;
      const interval = setInterval(() => {
        if (location.pathname !== lastPath) {
          lastPath = location.pathname;
          firedThresholds = new Set();
        }
      }, 1000);
      (handler as any)._resetInterval = interval;
    },
    uninstall() {
      if (handler) {
        window.removeEventListener("scroll", handler);
        clearInterval((handler as any)._resetInterval);
      }
    },
  };
}
