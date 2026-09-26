// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { resolveConfig } from "../src/core/config";
import { SCHEMA_VERSION, type CommerceEvent } from "../src/events/types";
import { EventValidator } from "../src/validation/validator";

const validator = new EventValidator();

function eventWithProperties(properties: Record<string, unknown>): CommerceEvent {
  return {
    eventId: "evt_1",
    eventType: "payment_information_added",
    schemaVersion: SCHEMA_VERSION,
    timestamp: "2026-01-01T00:00:00.000Z",
    identity: { anonymousId: "anon_A", userId: null, sessionId: "session_1" },
    context: { platform: "web" },
    commerce: { cartId: "cart_1" },
    properties,
  };
}

/**
 * These are the tests that make section 17 of the spec enforceable rather than
 * aspirational: no amount of careful documentation stops someone spreading a
 * checkout form into an event, so the validator has to refuse it.
 */
describe("sensitive payment data can never enter an event", () => {
  it("allows safe payment metadata", () => {
    expect(validator.validate(eventWithProperties({ paymentMethod: "card" })).valid).toBe(true);
  });

  it.each([
    ["cardNumber", "4111111111111111"],
    ["card_number", "4111111111111111"],
    ["CardNumber", "4111111111111111"],
    ["cvv", "123"],
    ["CVC", "123"],
    ["securityCode", "123"],
    ["pan", "4111111111111111"],
    ["expiryMonth", "12"],
    ["password", "hunter2"],
    ["ssn", "123-45-6789"],
    ["iban", "DE89370400440532013000"],
  ])("rejects an event carrying %s", (field, value) => {
    const result = validator.validate(eventWithProperties({ [field]: value }));

    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.message.includes("sensitive"))).toBe(true);
  });

  it("rejects sensitive fields nested inside an object", () => {
    const result = validator.validate(
      eventWithProperties({ checkout: { billing: { cardNumber: "4111111111111111" } } })
    );

    expect(result.valid).toBe(false);
    expect(result.errors[0].field).toBe("properties.checkout.billing.cardNumber");
  });

  it("rejects sensitive fields nested inside an array", () => {
    const result = validator.validate(eventWithProperties({ methods: [{ cvv: "123" }] }));

    expect(result.valid).toBe(false);
    expect(result.errors[0].field).toBe("properties.methods[0].cvv");
  });

  it("survives a circular structure instead of hanging", () => {
    const circular: Record<string, unknown> = { safe: "value" };
    circular.self = circular;

    expect(() => validator.validate(eventWithProperties(circular))).not.toThrow();
  });

  it("rejects provider credentials pasted into properties", () => {
    for (const field of ["secretKey", "privateKey", "apiSecret", "accessToken"]) {
      const result = validator.validate(eventWithProperties({ [field]: "whatever" }));
      expect(result.valid, field).toBe(false);
    }
  });
});

/**
 * The browser bundle must never be able to hold a provider credential. We can't
 * grep a bundle from a unit test, so we enforce it at the only place a
 * credential could realistically be introduced: client configuration.
 */
describe("provider credentials cannot be configured in the browser", () => {
  const base = { endpoint: "https://events.example.com" };

  it("accepts a publishable key", () => {
    expect(() => resolveConfig({ ...base, apiKey: "pk_live_abc123" })).not.toThrow();
  });

  it("rejects a Stripe-style secret key", () => {
    expect(() => resolveConfig({ ...base, apiKey: "sk_live_abc123" })).toThrow(/publishable key/i);
  });

  it("rejects an AWS access key id", () => {
    expect(() => resolveConfig({ ...base, apiKey: "AKIAIOSFODNN7EXAMPLE" })).toThrow(/publishable key/i);
  });

  it("rejects a pasted private key", () => {
    expect(() =>
      resolveConfig({ ...base, apiKey: "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----" })
    ).toThrow(/publishable key/i);
  });
});

describe("configuration validation", () => {
  it("requires an apiKey", () => {
    expect(() => resolveConfig({ endpoint: "https://e.example.com" } as never)).toThrow(/apiKey is required/);
  });

  it("requires an endpoint", () => {
    expect(() => resolveConfig({ apiKey: "pk_live_x" } as never)).toThrow(/endpoint is required/);
  });

  it("requires an absolute endpoint URL", () => {
    expect(() => resolveConfig({ apiKey: "pk_live_x", endpoint: "/v1/events" })).toThrow(/absolute http/);
  });

  it("rejects a non-positive batch size", () => {
    expect(() =>
      resolveConfig({ apiKey: "pk_live_x", endpoint: "https://e.example.com", maxBatchSize: 0 })
    ).toThrow(/maxBatchSize/);
  });

  it("applies documented defaults", () => {
    const config = resolveConfig({ apiKey: "pk_live_x", endpoint: "https://e.example.com" });

    expect(config.sessionTimeoutMs).toBe(30 * 60 * 1000);
    expect(config.maxBatchSize).toBe(20);
    expect(config.maxWaitMs).toBe(5000);
    expect(config.validateEvents).toBe(true);
  });
});
