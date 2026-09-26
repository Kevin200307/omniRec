// SPDX-License-Identifier: Apache-2.0
const ANON_ID_KEY = "omnirec_anon_id";
const SESSION_ID_KEY = "omnirec_session_id";
const USER_ID_KEY = "omnirec_user_id";

function uuid(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  // Fallback for older browsers/environments without crypto.randomUUID.
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function writeCookie(name: string, value: string, maxAgeSeconds: number): void {
  if (typeof document === "undefined") return;
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/; max-age=${maxAgeSeconds}; SameSite=Lax`;
}

/**
 * Anonymous ID persists across sessions (1 year cookie). Session ID resets
 * per browser session via sessionStorage. userId is set once the host app
 * calls identify() after login and is merged server-side against anonymousId.
 */
export class Identity {
  private _anonymousId: string;
  private _sessionId: string;
  private _userId: string | null;

  constructor() {
    this._anonymousId = readCookie(ANON_ID_KEY) ?? uuid();
    writeCookie(ANON_ID_KEY, this._anonymousId, 60 * 60 * 24 * 365);

    this._sessionId = this.readSessionId() ?? uuid();
    this.writeSessionId(this._sessionId);

    this._userId = this.readUserId();
  }

  get anonymousId(): string {
    return this._anonymousId;
  }

  get sessionId(): string {
    return this._sessionId;
  }

  get userId(): string | null {
    return this._userId;
  }

  identify(userId: string): void {
    this._userId = userId;
    if (typeof sessionStorage !== "undefined") {
      sessionStorage.setItem(USER_ID_KEY, userId);
    }
  }

  private readSessionId(): string | null {
    if (typeof sessionStorage === "undefined") return null;
    return sessionStorage.getItem(SESSION_ID_KEY);
  }

  private writeSessionId(id: string): void {
    if (typeof sessionStorage === "undefined") return;
    sessionStorage.setItem(SESSION_ID_KEY, id);
  }

  private readUserId(): string | null {
    if (typeof sessionStorage === "undefined") return null;
    return sessionStorage.getItem(USER_ID_KEY);
  }
}

export { uuid };
