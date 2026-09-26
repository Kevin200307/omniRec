// SPDX-License-Identifier: Apache-2.0
/**
 * First-party browser storage, wrapped so every read/write is safe.
 *
 * Every accessor here can throw or silently return null: private browsing,
 * blocked cookies, storage quota, SSR (no `document`/`window` at all). Callers
 * must work when storage is unavailable — identity then lives for one page load
 * only, which degrades tracking but never breaks the host application.
 *
 * We deliberately use first-party cookies + localStorage. No fingerprinting, no
 * IP-derived identity — see docs/identity.md.
 */

export interface KeyValueStore {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

/** Survives tab close and browser restart. Used for anonymousId. */
export class CookieStore implements KeyValueStore {
  constructor(private maxAgeSeconds: number) {}

  get(key: string): string | null {
    if (typeof document === "undefined") return null;
    try {
      const match = document.cookie.match(new RegExp(`(?:^|; )${escapeRegExp(key)}=([^;]*)`));
      return match ? decodeURIComponent(match[1]) : null;
    } catch {
      return null;
    }
  }

  set(key: string, value: string): void {
    if (typeof document === "undefined") return;
    try {
      const secure = typeof location !== "undefined" && location.protocol === "https:" ? "; Secure" : "";
      document.cookie =
        `${key}=${encodeURIComponent(value)}; path=/; max-age=${this.maxAgeSeconds}; SameSite=Lax${secure}`;
    } catch {
      // Cookies blocked — caller falls back to in-memory identity.
    }
  }

  remove(key: string): void {
    if (typeof document === "undefined") return;
    try {
      document.cookie = `${key}=; path=/; max-age=0; SameSite=Lax`;
    } catch {
      // ignore
    }
  }
}

/** Survives tab close. Used for sessionId + last-activity + userId. */
export class LocalStore implements KeyValueStore {
  get(key: string): string | null {
    try {
      if (typeof localStorage === "undefined") return null;
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  set(key: string, value: string): void {
    try {
      if (typeof localStorage === "undefined") return;
      localStorage.setItem(key, value);
    } catch {
      // Quota exceeded or storage disabled.
    }
  }

  remove(key: string): void {
    try {
      if (typeof localStorage === "undefined") return;
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/** Fallback when no browser storage is usable, and the store used by tests. */
export class MemoryStore implements KeyValueStore {
  private readonly map = new Map<string, string>();

  get(key: string): string | null {
    return this.map.has(key) ? this.map.get(key)! : null;
  }

  set(key: string, value: string): void {
    this.map.set(key, value);
  }

  remove(key: string): void {
    this.map.delete(key);
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** RFC 4122 v4, with a non-crypto fallback for old browsers and JSDOM. */
export function uuid(): string {
  try {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
      const bytes = crypto.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
  } catch {
    // fall through
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
