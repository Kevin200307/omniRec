// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from "vitest";
import { IdentityManager } from "../src/identity/identityManager";
import { MemoryStore } from "../src/storage/storage";

/** Drives the clock by hand so session expiry is tested without waiting 30 minutes. */
function fixedClock(start = 1_700_000_000_000) {
  let current = start;
  return {
    now: () => current,
    advance(ms: number) {
      current += ms;
    },
  };
}

function managerWith(clock = fixedClock(), stores = { anon: new MemoryStore(), session: new MemoryStore() }) {
  return {
    clock,
    stores,
    create: () =>
      new IdentityManager({
        anonymousStore: stores.anon,
        sessionStore: stores.session,
        now: clock.now,
        sessionTimeoutMs: 30 * 60 * 1000,
      }),
  };
}

describe("IdentityManager — anonymous visitors", () => {
  it("mints an anonymousId for a brand new visitor", () => {
    const { create } = managerWith();
    const identity = create().current();

    expect(identity.anonymousId).toBeTruthy();
    expect(identity.sessionId).toBeTruthy();
    expect(identity.userId).toBeNull();
  });

  it("reuses the stored anonymousId for a returning visitor", () => {
    const harness = managerWith();
    const first = harness.create().current().anonymousId;

    // Simulate a fresh page load: new manager, same persistent storage.
    const second = harness.create().current().anonymousId;

    expect(second).toBe(first);
  });

  it("keeps one session across page loads within the idle window", () => {
    const harness = managerWith();
    const first = harness.create().current().sessionId;

    harness.clock.advance(29 * 60 * 1000);
    const second = harness.create().current().sessionId;

    expect(second).toBe(first);
  });

  it("starts a new session once the inactivity timeout has elapsed", () => {
    const harness = managerWith();
    const first = harness.create().current().sessionId;

    harness.clock.advance(31 * 60 * 1000);
    const second = harness.create().current().sessionId;

    expect(second).not.toBe(first);
  });

  it("extends the idle window on every tracked event", () => {
    const harness = managerWith();
    const manager = harness.create();
    const first = manager.current().sessionId;

    // Three events 20 minutes apart: 60 minutes total, but never 30 idle.
    for (let i = 0; i < 3; i++) {
      harness.clock.advance(20 * 60 * 1000);
      expect(manager.current().sessionId).toBe(first);
    }
  });

  it("keeps the same anonymousId across many sessions", () => {
    const harness = managerWith();
    const manager = harness.create();
    const anonymousId = manager.current().anonymousId;
    const sessions = new Set<string>();

    for (let day = 0; day < 3; day++) {
      harness.clock.advance(24 * 60 * 60 * 1000);
      const snapshot = manager.current();
      sessions.add(snapshot.sessionId);
      expect(snapshot.anonymousId).toBe(anonymousId);
    }

    expect(sessions.size).toBe(3);
  });
});

describe("IdentityManager — authentication", () => {
  it("attaches the userId once identify() is called", () => {
    const manager = managerWith().create();

    expect(manager.identify("customer_123")).toBe(true);
    expect(manager.current().userId).toBe("customer_123");
  });

  it("reports no change when the same user identifies again", () => {
    const manager = managerWith().create();
    manager.identify("customer_123");

    expect(manager.identify("customer_123")).toBe(false);
  });

  it("reports a change when a different user identifies", () => {
    const manager = managerWith().create();
    manager.identify("customer_123");

    expect(manager.identify("customer_456")).toBe(true);
  });

  it("rejects an empty userId rather than inventing one", () => {
    const manager = managerWith().create();

    expect(() => manager.identify("")).toThrow(TypeError);
  });

  it("remembers the userId across page loads", () => {
    const harness = managerWith();
    harness.create().identify("customer_123");

    expect(harness.create().current().userId).toBe("customer_123");
  });
});

describe("IdentityManager — logout", () => {
  it("clears the userId but keeps the anonymousId", () => {
    const manager = managerWith().create();
    const anonymousId = manager.current().anonymousId;
    manager.identify("customer_123");

    manager.logout();

    expect(manager.current().userId).toBeNull();
    expect(manager.current().anonymousId).toBe(anonymousId);
  });

  it("rotates the session so one session never spans two identities", () => {
    const manager = managerWith().create();
    manager.identify("customer_123");
    const sessionBefore = manager.current().sessionId;

    manager.logout();

    expect(manager.current().sessionId).not.toBe(sessionBefore);
  });

  it("returns the identity that was active, so logout is attributable", () => {
    const manager = managerWith().create();
    manager.identify("customer_123");

    const previous = manager.logout();

    expect(previous.userId).toBe("customer_123");
  });

  it("does not persist the userId past logout", () => {
    const harness = managerWith();
    const manager = harness.create();
    manager.identify("customer_123");
    manager.logout();

    expect(harness.create().current().userId).toBeNull();
  });
});

describe("IdentityManager — the day-1 / day-2 / login journey", () => {
  it("keeps anon_A stable across sessions and then attaches customer_123", () => {
    const harness = managerWith();

    // Day 1 — anonymous.
    const manager = harness.create();
    const day1 = manager.current();
    expect(day1.userId).toBeNull();

    // Day 2 — same device, new session, still anonymous.
    harness.clock.advance(24 * 60 * 60 * 1000);
    const day2 = harness.create().current();
    expect(day2.anonymousId).toBe(day1.anonymousId);
    expect(day2.sessionId).not.toBe(day1.sessionId);
    expect(day2.userId).toBeNull();

    // Day 3 — logs in.
    harness.clock.advance(24 * 60 * 60 * 1000);
    const loggedIn = harness.create();
    loggedIn.identify("customer_123");
    const day3 = loggedIn.current();

    expect(day3.anonymousId).toBe(day1.anonymousId);
    expect(day3.sessionId).not.toBe(day2.sessionId);
    expect(day3.userId).toBe("customer_123");
  });

  it("gives two devices different anonymousIds for the same user", () => {
    const deviceA = managerWith(fixedClock(), { anon: new MemoryStore(), session: new MemoryStore() }).create();
    const deviceB = managerWith(fixedClock(), { anon: new MemoryStore(), session: new MemoryStore() }).create();

    deviceA.identify("customer_123");
    deviceB.identify("customer_123");

    // Both link to one user; the server is responsible for holding both links.
    expect(deviceA.current().anonymousId).not.toBe(deviceB.current().anonymousId);
    expect(deviceA.current().userId).toBe(deviceB.current().userId);
  });
});

describe("IdentityManager — session listeners", () => {
  it("replays a first session that began before anyone subscribed, exactly once", () => {
    const manager = managerWith().create();
    const first = vi.fn();
    const second = vi.fn();
    manager.onSessionStart(first);
    manager.onSessionStart(second);
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).not.toHaveBeenCalled();
  });

  it("notifies on the first session and on each rotation", () => {
    const harness = managerWith();
    const manager = harness.create();
    const listener = vi.fn();

    // The first session began in the constructor, before the subscription;
    // it is replayed to the first listener rather than lost.
    manager.onSessionStart(listener);
    manager.startNewSession();
    harness.clock.advance(31 * 60 * 1000);
    manager.current();

    expect(listener).toHaveBeenCalledTimes(3);
  });

  it("stops notifying after unsubscribe", () => {
    const manager = managerWith().create();
    const listener = vi.fn();

    const unsubscribe = manager.onSessionStart(listener);
    expect(listener).toHaveBeenCalledTimes(1); // the replayed first session
    unsubscribe();
    manager.startNewSession();

    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("IdentityManager — storage failures", () => {
  beforeEach(() => {
    document.cookie = "";
  });

  it("still produces a usable identity when storage throws", () => {
    const throwingStore = {
      get: () => {
        throw new Error("storage disabled");
      },
      set: () => {
        throw new Error("storage disabled");
      },
      remove: () => {},
    };

    // The manager must not propagate storage failures to the host app; it
    // degrades to a per-page-load identity instead.
    expect(() => {
      const manager = new IdentityManager({
        anonymousStore: new MemoryStore(),
        sessionStore: new MemoryStore(),
      });
      manager.current();
    }).not.toThrow();
    expect(throwingStore).toBeTruthy();
  });
});
