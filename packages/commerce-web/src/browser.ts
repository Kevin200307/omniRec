// SPDX-License-Identifier: Apache-2.0
/**
 * Script-tag build (`dist/omnirec.min.js`) for sites without a bundler.
 *
 *     <script>
 *       !function(w){var o=w.omnirec=w.omnirec||{q:[]};["track","identify","logout","flush"]
 *       .forEach(function(m){o[m]=o[m]||function(){o.q.push([m,[].slice.call(arguments)])}})}(window);
 *     </script>
 *     <script async src="/omnirec.min.js" data-endpoint="/omnirec"></script>
 *
 * The loader stub queues calls made before this file arrives; they are replayed
 * in order. Configure with data attributes on the script tag, or with
 * `window.omnirecConfig = { endpoint, ... }` set before it loads.
 */
import { createOmnirec, type OmnirecClient, type TrackOptions } from "./core/omnirec";
import type { OmnirecConfig } from "./core/config";
import type { OmnirecPlugin } from "./core/pipeline";
import { autocapture } from "./plugins/autocapture";
import { dom } from "./plugins/dom";
import { impressions } from "./plugins/impressions";

type QueuedCall = [method: string, args: unknown[]];

export interface OmnirecGlobal {
  track(event: string, data?: Record<string, unknown>, options?: TrackOptions): string | undefined;
  identify(userId: string | { userId: string; traits?: Record<string, unknown> }): void;
  logout(): void;
  flush(): Promise<void>;
  client: OmnirecClient;
}

declare global {
  interface Window {
    omnirec?: Partial<OmnirecGlobal> & { q?: QueuedCall[] };
    omnirecConfig?: Partial<OmnirecConfig> & { pluginNames?: string[] };
  }
}

const FACTORIES: Record<string, () => OmnirecPlugin> = { dom, impressions, autocapture };

function boot(): void {
  const script = document.currentScript as HTMLScriptElement | null;
  const attrs = script?.dataset ?? {};
  const fromWindow = window.omnirecConfig ?? {};
  const endpoint = fromWindow.endpoint ?? attrs.endpoint;
  if (!endpoint) {
    console.error('[omnirec] set data-endpoint on the script tag or window.omnirecConfig = { endpoint }');
    return;
  }
  const names = fromWindow.pluginNames ?? (attrs.plugins ?? "dom,impressions,autocapture").split(",");
  const plugins = names
    .map((name) => name.trim())
    .filter((name) => name && name !== "none")
    .map((name) => {
      const factory = FACTORIES[name];
      if (!factory) console.warn(`[omnirec] unknown plugin "${name}"`);
      return factory?.();
    })
    .filter((plugin): plugin is OmnirecPlugin => Boolean(plugin));

  const client = createOmnirec({
    ...fromWindow,
    endpoint,
    apiKey: fromWindow.apiKey ?? attrs.apiKey,
    debug: fromWindow.debug ?? attrs.debug === "true",
    plugins: [...plugins, ...(fromWindow.plugins ?? [])],
  });

  const api: OmnirecGlobal = {
    track: (event, data, options) => client.trackUntyped(event, data ?? {}, options ?? {}),
    identify: (user) => client.identify(user),
    logout: () => client.logout(),
    flush: () => client.flush(),
    client,
  };

  const queued = window.omnirec?.q ?? [];
  window.omnirec = api;
  for (const [method, args] of queued) {
    const fn = (api as unknown as Record<string, (...a: unknown[]) => unknown>)[method];
    if (typeof fn === "function") fn(...args);
    else console.warn(`[omnirec] ignored queued call to unknown method "${method}"`);
  }
}

if (typeof window !== "undefined" && typeof document !== "undefined") boot();
