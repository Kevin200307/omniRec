// SPDX-License-Identifier: Apache-2.0
import { toData } from "../compat/v1";
import { collectContext } from "../context/contextCollector";
import { SCHEMA_VERSION, type CommerceData, type CommerceEvent, type EventType } from "../events/types";
import type { EventDataMap } from "../events/generated/catalog";
import { IdentityManager, type IdentitySnapshot } from "../identity/identityManager";
import { uuid } from "../storage/storage";
import { Batcher } from "../transport/batcher";
import { OfflineBuffer } from "../transport/offlineBuffer";
import { Transport } from "../transport/transport";
import { EventValidator } from "../validation/validator";
import { resolveConfig, type OmnirecConfig, type ResolvedConfig } from "./config";
import type { EmitOptions, EventEmitter } from "./emitter";
import { runPipeline, type Middleware, type OmnirecPlugin, type PluginHost } from "./pipeline";

/**
 * Custom events from your tracking plan. `omnirec generate` writes an
 * augmentation of this interface into your project so `track()` knows them:
 *
 *     declare module "@omnirec/commerce-web" {
 *       interface OmnirecCustomEvents {
 *         action_x_clicked: { variant: "a" | "b" };
 *       }
 *     }
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface OmnirecCustomEvents {}

/** Every event `track()` accepts: the standard catalog plus your plan. */
export type KnownEvents = EventDataMap & OmnirecCustomEvents;

export interface TrackOptions {
  /** Free-form attributes that are not part of the event's data. */
  properties?: Record<string, unknown>;
  /** Use this id instead of a random one, so a retried business fact deduplicates. */
  eventId?: string;
}

/** `data` is optional for events that require none. */
type TrackArgs<E extends keyof KnownEvents> = {} extends KnownEvents[E]
  ? [data?: KnownEvents[E], options?: TrackOptions]
  : [data: KnownEvents[E], options?: TrackOptions];

export interface IdentifyInput {
  userId: string;
  traits?: Record<string, unknown>;
}

/**
 * The browser client: identity, sessions, validation, the middleware pipeline,
 * batching and transport. Everything else (HTML attributes, autocapture,
 * consent, debug output) is a plugin, so a page only ships what it uses.
 *
 *     const omnirec = createOmnirec({ endpoint: "/omnirec" });
 *     omnirec.track("product_added_to_cart", { product: { id: "P100", quantity: 1 } });
 */
export class OmnirecClient implements EventEmitter {
  protected readonly config: ResolvedConfig;
  protected readonly identity: IdentityManager;
  protected readonly batcher: Batcher;
  private readonly validator = new EventValidator();
  private readonly beforeValidate: Middleware[] = [];
  private readonly afterValidate: Middleware[] = [];
  private readonly teardowns: Array<() => void> = [];
  private readonly installed = new Set<string>();
  protected destroyed = false;

  constructor(config: OmnirecConfig) {
    this.config = resolveConfig(config);
    this.identity = new IdentityManager({
      sessionTimeoutMs: this.config.sessionTimeoutMs,
      cookieDomain: this.config.cookieDomain,
    });

    const transport = new Transport({
      endpoint: this.config.endpoint,
      apiKey: this.config.apiKey,
      tenantId: this.config.tenantId,
      maxRetries: this.config.maxRetries,
      fetchImpl: this.config.fetchImpl,
    });

    this.batcher = new Batcher(transport, {
      maxBatchSize: this.config.maxBatchSize,
      maxWaitMs: this.config.maxWaitMs,
      maxEventAgeMs: this.config.maxEventAgeMs,
      offlineBuffer: new OfflineBuffer({ maxEvents: this.config.maxOfflineEvents }),
      onDrop: (events, reason) => this.config.onError(new Error(`dropped ${events.length} event(s) (${reason})`)),
    });

    this.afterValidate.push(...this.config.middleware);

    if (this.config.autoTrackSessions) {
      // Subscribe before touching identity so the very first session — created
      // lazily on the first read — still produces session_started.
      this.identity.onSessionStart(() => this.send("session_started", {}, {}));
      this.identity.current();
    }

    for (const plugin of this.config.plugins) this.use(plugin);
  }

  /**
   * Tracks an event. The name autocompletes from the catalog and your plan, and
   * `data` is checked against that event's definition at compile time.
   *
   * Returns the event id, or undefined if the event was refused (invalid, or
   * the client was destroyed).
   */
  track<E extends keyof KnownEvents & string>(event: E, ...args: TrackArgs<E>): string | undefined {
    const [data, options] = args as [Record<string, unknown> | undefined, TrackOptions | undefined];
    return this.send(event, data ?? {}, options ?? {});
  }

  /**
   * Tracks an event whose name is only known at runtime, for example from an
   * HTML attribute. Skips compile-time checks; the runtime checks still apply.
   */
  trackUntyped(event: string, data: Record<string, unknown> = {}, options: TrackOptions = {}): string | undefined {
    return this.send(event, data, options);
  }

  /** The v1 path used by the per-event helper methods: converts the flat payload to v2 blocks. */
  emit(
    eventType: EventType,
    commerce: CommerceData = {},
    properties: Record<string, unknown> = {},
    options: EmitOptions = {}
  ): string | undefined {
    return this.send(eventType, toData(commerce) as Record<string, unknown>, { properties, eventId: options.eventId });
  }

  /** Installs a plugin (or several). Installing the same plugin name twice is a no-op. */
  use(plugin: OmnirecPlugin | OmnirecPlugin[]): this {
    if (Array.isArray(plugin)) {
      for (const p of plugin) this.use(p);
      return this;
    }
    if (this.installed.has(plugin.name)) return this;
    this.installed.add(plugin.name);
    if (plugin.middleware) {
      (plugin.phase === "before-validate" ? this.beforeValidate : this.afterValidate).push(plugin.middleware);
    }
    const teardown = plugin.setup?.(this.host());
    if (typeof teardown === "function") this.teardowns.push(teardown);
    return this;
  }

  /**
   * Associates the current anonymous visitor with a merchant user id and emits
   * an `identify` event so the server can record the link. Calling it again
   * with the same userId is a no-op, so it's safe to call on every page load.
   *
   * This does not rewrite past events: they keep the anonymousId they were
   * captured with, and the server resolves history through the link.
   */
  identify(input: IdentifyInput | string): void {
    const userId = typeof input === "string" ? input : input.userId;
    const traits = typeof input === "string" ? undefined : input.traits;
    if (this.identity.identify(userId)) {
      this.send("identify", {}, { properties: traits ? { traits } : {} });
    }
  }

  /** Clears the authenticated identity, keeps the anonymousId, rotates the session. */
  logout(): void {
    this.identity.logout();
  }

  /** Current identity, for debugging and for server-rendered pages that need it. */
  getIdentity(): IdentitySnapshot {
    return this.identity.peek();
  }

  /** Forces an immediate send. Resolves once the batch has been delivered or given up on. */
  async flush(): Promise<void> {
    await this.batcher.flush();
  }

  /** Number of events buffered in memory plus on disk. */
  pending(): number {
    return this.batcher.pending();
  }

  /** Flushes, removes plugin listeners and detaches every listener. Call on SPA teardown / hot reload. */
  destroy(): void {
    if (this.destroyed) return;
    for (const teardown of this.teardowns.splice(0).reverse()) {
      try {
        teardown();
      } catch (error) {
        this.config.onError(error instanceof Error ? error : new Error(String(error)));
      }
    }
    this.batcher.destroy();
    this.destroyed = true;
  }

  protected send(name: string, data: Record<string, unknown>, options: TrackOptions): string | undefined {
    if (this.destroyed) {
      this.config.onError(new Error(`ignored "${name}" — client has been destroyed`));
      return undefined;
    }

    const event: CommerceEvent = {
      eventId: options.eventId ?? uuid(),
      event: name,
      schemaVersion: SCHEMA_VERSION,
      source: "browser",
      timestamp: new Date().toISOString(),
      identity: this.identity.current(),
      context: collectContext(),
      data,
      properties: options.properties ?? {},
    };

    let rejected = false;
    const validate: Middleware = (current, next) => {
      if (this.config.validateEvents) {
        const result = this.validator.validate(current);
        if (!result.valid) {
          rejected = true;
          const detail = result.errors.map((e) => `${e.field}: ${e.message}`).join("; ");
          this.config.onError(new Error(`invalid "${current.event}" event not sent — ${detail}`));
          return;
        }
        if (result.unknownEvent && this.config.debug) {
          console.debug(`[omnirec] "${current.event}" is not in the standard catalog; the server checks it against your plan`);
        }
      }
      next(current);
    };

    runPipeline(
      event,
      [...this.beforeValidate, validate, ...this.afterValidate],
      (final) => {
        if (this.config.debug) console.debug(`[omnirec] ${final.event}`, final);
        this.batcher.enqueue(final);
      },
      (error) => this.config.onError(error)
    );
    return rejected ? undefined : event.eventId;
  }

  private host(): PluginHost {
    return {
      track: (event, data, options) => this.trackUntyped(event, data ?? {}, options ?? {}),
      config: { endpoint: this.config.endpoint, debug: this.config.debug },
      reportError: (error) => this.config.onError(error),
    };
  }
}

export function createOmnirec(config: OmnirecConfig): OmnirecClient {
  return new OmnirecClient(config);
}
