// SPDX-License-Identifier: Apache-2.0
import { Identity, uuid } from "./identity";
import { Consent } from "./consent";
import { Transport } from "./transport";
import { EventQueue } from "./queue";
import type { EventCategory, EventType, OmnirecConfig, OutgoingEvent, Plugin, PluginName } from "./types";
import { dwellTimePlugin } from "./plugins/dwellTime";
import { scrollDepthPlugin } from "./plugins/scrollDepth";
import { cartPlugin } from "./plugins/cart";

const PLUGIN_REGISTRY: Record<PluginName, () => Plugin> = {
  dwellTime: dwellTimePlugin,
  scrollDepth: scrollDepthPlugin,
  cart: cartPlugin,
  wishlist: () => ({
    name: "wishlist",
    // No auto-instrumentation — wishlist_add is fired explicitly via
    // track() from the host app's own "save" button, since there's no
    // reliable DOM signal for "this is a wishlist action" to hook into.
    install() {},
  }),
};

export class Tracker {
  private identity: Identity;
  private consent: Consent;
  private queue: EventQueue;
  private installedPlugins: Plugin[] = [];
  private ready = false;

  constructor(private config: OmnirecConfig) {
    this.identity = new Identity();
    this.consent = new Consent();

    const transport = new Transport(config.endpoint, config.tenantId);
    this.queue = new EventQueue(transport, config.batchSize ?? 20, config.intervalMs ?? 5000);

    const requireConsent = config.requireConsent ?? true;
    if (!requireConsent || this.consent.get() === "granted") {
      this.installPlugins();
    } else {
      this.consent.onChange((state) => {
        if (state === "granted") this.installPlugins();
        if (state === "denied") this.uninstallPlugins();
      });
    }
  }

  private installPlugins(): void {
    if (this.ready) return;
    this.ready = true;
    for (const name of this.config.plugins ?? []) {
      const factory = PLUGIN_REGISTRY[name];
      if (!factory) continue;
      const plugin = factory();
      plugin.install({
        track: (eventType, category, payload) => this.track(eventType, category, payload),
      });
      this.installedPlugins.push(plugin);
    }
  }

  private uninstallPlugins(): void {
    for (const plugin of this.installedPlugins) plugin.uninstall?.();
    this.installedPlugins = [];
    this.ready = false;
  }

  identify(userId: string): void {
    this.identity.identify(userId);
  }

  grantConsent(): void {
    this.consent.grant();
  }

  denyConsent(): void {
    this.consent.deny();
  }

  /**
   * The one call the host app ever needs for anything not covered by a
   * plugin — explicit signals (reviews, wishlist) or custom events.
   * Not gated by consent: a direct call is deliberate first-party collection.
   */
  track(eventType: EventType, category: EventCategory = "EXPLICIT", payload: Record<string, unknown> = {}): void {
    const event: OutgoingEvent = {
      eventId: uuid(),
      tenantId: this.config.tenantId,
      userId: this.identity.userId,
      anonymousId: this.identity.anonymousId,
      sessionId: this.identity.sessionId,
      eventType,
      category,
      payload,
      context: {},
      capturedAt: new Date().toISOString(),
      // When the host app opts out of consent-gating altogether
      // (requireConsent: false — e.g. no GDPR/CCPA obligation), treat that
      // as an affirmative decision rather than leaving the backend to drop
      // every implicit/contextual event because consent was never
      // explicitly granted through the Consent API.
      consent: (this.config.requireConsent ?? true) ? this.consent.get() : "granted",
    };
    this.queue.enqueue(event);
  }

  flush(): void {
    this.queue.flush();
  }

  destroy(): void {
    this.uninstallPlugins();
    this.queue.destroy();
  }
}
