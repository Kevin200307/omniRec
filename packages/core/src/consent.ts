const CONSENT_KEY = "omnirec_consent";

export type ConsentState = "granted" | "denied" | "unknown";

/**
 * Gate for implicit/contextual tracking plugins (GDPR/CCPA). Explicit calls
 * to track() from the host app are never gated by this — the developer
 * called track() deliberately, so that's on them, same as any other
 * first-party data collection they already do.
 */
export class Consent {
  private state: ConsentState;
  private listeners: Array<(state: ConsentState) => void> = [];

  constructor() {
    this.state = this.read();
  }

  get(): ConsentState {
    return this.state;
  }

  grant(): void {
    this.set("granted");
  }

  deny(): void {
    this.set("denied");
  }

  onChange(fn: (state: ConsentState) => void): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== fn);
    };
  }

  private set(state: ConsentState): void {
    this.state = state;
    if (typeof localStorage !== "undefined") {
      try {
        localStorage.setItem(CONSENT_KEY, state);
      } catch {
        // Storage unavailable (private mode, blocked cookies) — state still
        // holds for this page load, just doesn't persist across visits.
      }
    }
    for (const listener of this.listeners) listener(state);
  }

  private read(): ConsentState {
    if (typeof localStorage === "undefined") return "unknown";
    try {
      const stored = localStorage.getItem(CONSENT_KEY);
      return stored === "granted" || stored === "denied" ? stored : "unknown";
    } catch {
      return "unknown";
    }
  }
}
