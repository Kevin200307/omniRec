import { compact, type EventEmitter } from "../core/emitter";

/**
 * The identity side-effects UserTracker needs, kept as a narrow interface so
 * the tracker can be tested without a whole client.
 */
export interface IdentityActions {
  identify(userId: string, traits?: Record<string, unknown>): void;
  logout(): void;
}

export interface UserInput {
  userId: string;
  [key: string]: unknown;
}

/**
 * The only tracker with side effects beyond emitting: logging in establishes
 * the anonymous -> user link, and logging out clears the authenticated
 * identity (keeping the anonymousId, rotating the session).
 */
export class UserTracker {
  constructor(
    private readonly emitter: EventEmitter,
    private readonly identity: IdentityActions
  ) {}

  /** Call on registration. Identifies first, so the event itself carries the userId. */
  registered(input: UserInput): void {
    const { userId, ...rest } = input;
    this.identity.identify(userId, rest);
    this.emitter.emit("user_registered", {}, compact(rest));
  }

  loggedIn(input: UserInput): void {
    const { userId, ...rest } = input;
    this.identity.identify(userId, rest);
    this.emitter.emit("user_logged_in", {}, compact(rest));
  }

  /**
   * Emits `user_logged_out` *before* clearing, so the event is attributed to
   * the user who actually logged out rather than to an anonymous visitor.
   */
  loggedOut(properties: Record<string, unknown> = {}): void {
    this.emitter.emit("user_logged_out", {}, compact(properties));
    this.identity.logout();
  }

  profileUpdated(input: UserInput): void {
    const { userId, ...rest } = input;
    this.identity.identify(userId, rest);
    this.emitter.emit("user_profile_updated", {}, compact(rest));
  }
}
