// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import catalog from "../src/events/generated/catalog.json";
import { EVENT_ALIASES, EVENT_NAMES, EVENT_RULES } from "../src/events/generated/catalog";
import type { CommerceEvent } from "../src/events/types";
import { EventValidator } from "../src/validation/validator";

/**
 * Every catalog event, checked against the browser validator from its own
 * example. The Java validator runs the same examples
 * (CatalogDrivenValidationTest), so the two languages agree on every event.
 */
interface CatalogEvent {
  name: string;
  required: string[];
  example?: Record<string, unknown>;
}

const validator = new EventValidator();

function envelope(name: string, example: Record<string, unknown> = {}): CommerceEvent {
  const { identity, ...data } = example as { identity?: { userId?: string } };
  return {
    eventId: `evt_${name}`,
    event: name,
    schemaVersion: "2.0",
    source: "browser",
    timestamp: "2026-10-01T12:00:00.000Z",
    identity: { anonymousId: "anon_A", sessionId: "s1", userId: identity?.userId ?? null },
    context: { platform: "web" },
    data: data as CommerceEvent["data"],
    properties: {},
  };
}

function without(example: Record<string, unknown>, path: string): Record<string, unknown> {
  const copy = structuredClone(example);
  const parts = path.split(".");
  let current = copy as Record<string, unknown>;
  for (const part of parts.slice(0, -1)) current = (current[part] ?? {}) as Record<string, unknown>;
  delete current[parts[parts.length - 1]];
  return copy;
}

const wire = (path: string) => (path.startsWith("identity.") ? path : `data.${path}`);
const events = (catalog as { events: CatalogEvent[] }).events;

describe("catalog examples", () => {
  it("covers the whole catalog", () => {
    expect(events.map((e) => e.name).sort()).toEqual([...EVENT_NAMES].sort());
    expect(events.length).toBeGreaterThan(150);
  });

  for (const event of events) {
    it(`${event.name}: its example is valid and each required field is enforced`, () => {
      if (event.required.length === 0) {
        expect(validator.validate(envelope(event.name)).valid).toBe(true);
        return;
      }
      expect(event.example, `${event.name} needs an example`).toBeDefined();
      const ok = validator.validate(envelope(event.name, event.example));
      expect(ok.errors).toEqual([]);
      for (const path of event.required) {
        const result = validator.validate(envelope(event.name, without(event.example!, path)));
        expect(result.valid, `${event.name} without ${path}`).toBe(false);
        expect(result.errors.map((e) => e.field)).toContain(wire(path));
      }
    });
  }
});

describe("aliases", () => {
  it("share the canonical event's rules and never shadow a canonical name", () => {
    const canonical = new Set<string>(EVENT_NAMES);
    for (const [alias, name] of Object.entries(EVENT_ALIASES)) {
      expect(canonical.has(alias), alias).toBe(false);
      expect(canonical.has(name), name).toBe(true);
      expect(EVENT_RULES[alias]).toEqual(EVENT_RULES[name]);
    }
    expect(EVENT_ALIASES.add_to_cart).toBe("product_added_to_cart");
  });

  it("validate under the alias name with the full rules (debug plugin, tests)", () => {
    const full = new EventValidator(EVENT_RULES);
    expect(full.validate(envelope("add_to_cart", { product: { id: "P1" } })).errors.map((e) => e.field)).toContain(
      "data.product.quantity"
    );
    expect(full.validate(envelope("add_to_cart", { product: { id: "P1", quantity: 1 } })).valid).toBe(true);
  });

  it("pass the lean production check, leaving them to the server", () => {
    expect(validator.validate(envelope("add_to_cart", { product: { id: "P1" } })).valid).toBe(true);
  });
});
