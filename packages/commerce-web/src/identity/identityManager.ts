// SPDX-License-Identifier: Apache-2.0
import { CookieStore, LocalStore, MemoryStore, uuid, type KeyValueStore } from "../storage/storage";

const ANON_KEY = "omnirec_anonymous_id";
const SESSION_KEY = "omnirec_session_id";
const SESSION_ACTIVITY_KEY = "omnirec_session_last_activity";
const USER_KEY = "omnirec_user_id";

const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365;
export const DEFAULT_SESSION_TIMEOUT_MS = 30 * 60 * 1000;

export interface IdentitySnapshot {
  anonymousId: string;
  sessionId: string;
  userId: string | null;
}

export interface IdentityManagerOptions {
  /** Idle time after which the next event opens a new session. Default 30 minutes. */
  sessionTimeoutMs?: number;
  /** Injected in tests; defaults to real browser storage. */
  anonymousStore?: KeyValueStore;
  sessionStore?: KeyValueStore;
  /** Injected in tests so session expiry can be driven without real time passing. */
  now?: () => number;
}

export type SessionStartListener = (sessionId: string, previousSessionId: string | null) => void;

/**
 * Owns the three identities and the rules that relate them.
 *
 * ## anonymousId
 * A first-party cookie, 1 year. Created on first visit, never rotated by the SDK
 * and never cleared on logout — the same device staying recognisable across
 * logout is the entire point of anonymous behavioural data.
 *
 * ## sessionId
 * Stored in localStorage alongside a last-activity timestamp. A new session
 * starts when either:
 *   - no session exists yet, or
 *   - more than `sessionTimeoutMs` (default 30 min) has elapsed since the last
 *     tracked event, or
 *   - the merchant calls `logout()` (so one session never spans two identities).
 *
 * localStorage rather than sessionStorage is deliberate: sessionStorage is
 * per-tab and dies on tab close, which would both fragment a single visit
 * across tabs and end a session the moment someone closes a tab to come back
 * two minutes later. The idle timeout is the policy; tab lifetime is not.
 *
 * ## userId
 * Supplied by the merchant via `identify()`. The SDK never invents one.
 *
 * Linking anonymous -> authenticated is NOT done by rewriting identity here.
 * `identify()` returns a flag telling the client to emit an `identify` event;
 * the server stores `anon -> user` as a separate link record. See docs/identity.md.
 */
export class IdentityManager {
  private readonly anonymousStore: KeyValueStore;
  private readonly sessionStore: KeyValueStore;
  private readonly sessionTimeoutMs: number;
  private readonly now: () => number;
  private readonly sessionListeners: SessionStartListener[] = [];

  private anonymousId: string;
  private sessionId: string;
  private userId: string | null;

  constructor(options: IdentityManagerOptions = {}) {
    this.sessionTimeoutMs = options.sessionTimeoutMs ?? DEFAULT_SESSION_TIMEOUT_MS;
    this.now = options.now ?? (() => Date.now());
    this.anonymousStore = options.anonymousStore ?? defaultAnonymousStore();
    this.sessionStore = options.sessionStore ?? defaultSessionStore();

    this.anonymousId = this.anonymousStore.get(ANON_KEY) ?? "";
    if (!this.anonymousId) {
      this.anonymousId = uuid();
      this.anonymousStore.set(ANON_KEY, this.anonymousId);
    }

    this.userId = this.sessionStore.get(USER_KEY);
    this.sessionId = "";
    this.sessionId = this.resolveSessionId();
  }

  /**
   * Fires whenever a new session begins, including the first one. The client
   * subscribes to this to emit `session_started` without the merchant having to.
   */
  onSessionStart(listener: SessionStartListener): () => void {
    this.sessionListeners.push(listener);
    return () => {
      const index = this.sessionListeners.indexOf(listener);
      if (index >= 0) this.sessionListeners.splice(index, 1);
    };
  }

  /**
   * The identity to stamp on an event being tracked right now. Reading this
   * both rolls the session over if it has expired and extends the idle window,
   * so an active visitor never times out mid-visit.
   */
  current(): IdentitySnapshot {
    const sessionId = this.resolveSessionId();
    this.touch();
    return { anonymousId: this.anonymousId, sessionId, userId: this.userId };
  }

  /** Read-only view — does NOT extend the session. For assertions and diagnostics. */
  peek(): IdentitySnapshot {
    return { anonymousId: this.anonymousId, sessionId: this.sessionId, userId: this.userId };
  }

  /**
   * Associates the current anonymous visitor with a merchant user id.
   * Returns true when this is a new association that the caller should emit an
   * `identify` event for; false when the same user is already identified, so
   * repeated identify() calls on every page load don't spam the pipeline.
   */
  identify(userId: string): boolean {
    if (!userId || typeof userId !== "string") {
      throw new TypeError("identify() requires a non-empty userId string");
    }
    const previous = this.userId;
    const changed = previous !== userId;
    this.userId = userId;
    this.sessionStore.set(USER_KEY, userId);
    // A different user taking over without logging out first (a shared
    // device, an account switch) gets a fresh session, exactly as logout
    // would give them — one session never contains two people's behaviour.
    if (changed && previous !== null) {
      this.startNewSession();
    }
    return changed;
  }

  /**
   * Clears the authenticated identity but keeps the anonymousId, and rotates
   * the session so no single session contains events from two different users.
   * Returns the identity that was active before the reset, so the caller can
   * attribute the `user_logged_out` event to the user who actually logged out.
   */
  logout(): IdentitySnapshot {
    const previous = this.current();
    this.userId = null;
    this.sessionStore.remove(USER_KEY);
    this.startNewSession();
    return previous;
  }

  /** Ends the current session and opens a fresh one. */
  startNewSession(): string {
    const previousSessionId = this.sessionId || null;
    this.sessionId = uuid();
    this.sessionStore.set(SESSION_KEY, this.sessionId);
    this.touch();
    for (const listener of [...this.sessionListeners]) {
      listener(this.sessionId, previousSessionId);
    }
    return this.sessionId;
  }

  /** True when the idle window has elapsed since the last tracked event. */
  isSessionExpired(): boolean {
    const lastActivity = Number(this.sessionStore.get(SESSION_ACTIVITY_KEY));
    if (!Number.isFinite(lastActivity) || lastActivity <= 0) return true;
    return this.now() - lastActivity > this.sessionTimeoutMs;
  }

  private resolveSessionId(): string {
    const stored = this.sessionStore.get(SESSION_KEY);
    if (!stored || this.isSessionExpired()) {
      return this.startNewSession();
    }
    this.sessionId = stored;
    return stored;
  }

  private touch(): void {
    this.sessionStore.set(SESSION_ACTIVITY_KEY, String(this.now()));
  }
}

function defaultAnonymousStore(): KeyValueStore {
  if (typeof document === "undefined") return new MemoryStore();
  return new CookieStore(ONE_YEAR_SECONDS);
}

function defaultSessionStore(): KeyValueStore {
  if (typeof localStorage === "undefined") return new MemoryStore();
  return new LocalStore();
}
