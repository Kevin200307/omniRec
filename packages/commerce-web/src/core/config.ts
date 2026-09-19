import { DEFAULT_SESSION_TIMEOUT_MS } from "../identity/identityManager";

export interface CommerceConfig {
  /**
   * Publishable key, e.g. "pk_live_...". It authorises event writes for one
   * tenant and nothing else. Provider credentials (AWS keys, Google service
   * accounts) must never reach the browser — see docs/security.md.
   */
  apiKey: string;
  /** Base URL of the Event API, e.g. "https://events.example.com". */
  endpoint: string;
  tenantId?: string;
  /** Idle timeout before a new session begins. Default 30 minutes. */
  sessionTimeoutMs?: number;
  /** Emit session_started automatically on first load / after expiry. Default true. */
  autoTrackSessions?: boolean;
  /** Measure time-on-product and attach dwellTimeMs to product_viewed. Default true. */
  autoTrackDwellTime?: boolean;
  maxBatchSize?: number;
  maxWaitMs?: number;
  maxRetries?: number;
  maxOfflineEvents?: number;
  /**
   * Buffered events older than this are dropped instead of sent. Keep it below
   * the Event API's deduplication window (24h by default) or a resend of an
   * event that did land could be double-counted. Default 12 hours.
   */
  maxEventAgeMs?: number;
  /** Reject invalid events client-side instead of sending them. Default true. */
  validateEvents?: boolean;
  /** Surfaces validation failures and dropped batches. Defaults to console.warn. */
  onError?: (error: Error) => void;
  debug?: boolean;
  fetchImpl?: typeof fetch;
}

export interface ResolvedConfig extends Required<Omit<CommerceConfig, "tenantId" | "onError" | "fetchImpl">> {
  tenantId?: string;
  onError: (error: Error) => void;
  fetchImpl?: typeof fetch;
}

/**
 * Fails fast on a missing key or endpoint. A tracker that silently no-ops
 * because of a typo in configuration is far worse than one that throws at
 * startup, when the developer is looking right at it.
 */
export function resolveConfig(config: CommerceConfig): ResolvedConfig {
  if (!config || typeof config !== "object") {
    throw new TypeError("createCommerceClient(config) requires a configuration object");
  }
  if (!isNonEmpty(config.apiKey)) {
    throw new TypeError("commerce config: apiKey is required");
  }
  if (!isNonEmpty(config.endpoint)) {
    throw new TypeError("commerce config: endpoint is required");
  }
  if (!/^https?:\/\//i.test(config.endpoint)) {
    throw new TypeError(`commerce config: endpoint must be an absolute http(s) URL, got "${config.endpoint}"`);
  }
  if (looksLikeSecret(config.apiKey)) {
    throw new TypeError(
      "commerce config: apiKey looks like a secret credential. Only a publishable key (pk_...) belongs in browser code."
    );
  }

  const debug = config.debug ?? false;
  return {
    apiKey: config.apiKey,
    endpoint: config.endpoint,
    tenantId: config.tenantId,
    sessionTimeoutMs: positive(config.sessionTimeoutMs, DEFAULT_SESSION_TIMEOUT_MS, "sessionTimeoutMs"),
    autoTrackSessions: config.autoTrackSessions ?? true,
    autoTrackDwellTime: config.autoTrackDwellTime ?? true,
    maxBatchSize: positive(config.maxBatchSize, 20, "maxBatchSize"),
    maxWaitMs: positive(config.maxWaitMs, 5000, "maxWaitMs"),
    maxRetries: nonNegative(config.maxRetries, 3, "maxRetries"),
    maxOfflineEvents: positive(config.maxOfflineEvents, 500, "maxOfflineEvents"),
    maxEventAgeMs: positive(config.maxEventAgeMs, 12 * 60 * 60 * 1000, "maxEventAgeMs"),
    validateEvents: config.validateEvents ?? true,
    debug,
    onError:
      config.onError ??
      ((error: Error) => {
        if (debug || typeof process === "undefined" || process.env?.NODE_ENV !== "production") {
          console.warn(`[omnirec] ${error.message}`);
        }
      }),
    fetchImpl: config.fetchImpl,
  };
}

/**
 * Catches the specific mistake this whole architecture exists to prevent:
 * pasting a provider credential into frontend config. Not exhaustive — it is a
 * guardrail against the obvious slip, not a security boundary.
 */
function looksLikeSecret(apiKey: string): boolean {
  return (
    /^sk_/i.test(apiKey) ||
    /^AKIA[0-9A-Z]{16}$/.test(apiKey) ||
    /^ASIA[0-9A-Z]{16}$/.test(apiKey) ||
    /-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(apiKey)
  );
}

function isNonEmpty(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function positive(value: number | undefined, fallback: number, name: string): number {
  if (value === undefined) return fallback;
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    throw new TypeError(`commerce config: ${name} must be a positive number`);
  }
  return value;
}

function nonNegative(value: number | undefined, fallback: number, name: string): number {
  if (value === undefined) return fallback;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new TypeError(`commerce config: ${name} must be zero or greater`);
  }
  return value;
}
