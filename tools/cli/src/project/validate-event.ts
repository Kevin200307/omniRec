// SPDX-License-Identifier: Apache-2.0
import { NAME_PATTERN, type EventIndex, type RuntimeEvent, type RuntimeField } from "./runtime";

export interface EventProblem {
  field: string;
  message: string;
}

export interface EventCheck {
  valid: boolean;
  errors: EventProblem[];
  /** The canonical event, when the name or alias is known. */
  event?: RuntimeEvent;
  /** Neither in the catalog nor in the plan. The collector accepts it in permissive mode, for storage only. */
  unplanned: boolean;
}

/** Field names that must never be tracked, at any depth (same list as the SDKs and the collector). */
const SENSITIVE = new Set([
  "cardnumber", "cardno", "pan", "cvv", "cvc", "cvv2", "securitycode", "cardsecuritycode", "expirymonth",
  "expiryyear", "cardexpiry", "password", "passwd", "pin", "ssn", "socialsecuritynumber", "accesstoken",
  "refreshtoken", "apikey", "apisecret", "secretkey", "privatekey", "authorization", "creditcard", "iban",
]);

const MONEY = /^-?\d+(\.\d+)?$/;

/**
 * The collector's checks, for `omnirec dev`: envelope fields, the event's
 * required fields and constraints from the catalog or plan, and the
 * sensitive-field backstop. Paths in errors are wire paths (`data.product.id`).
 */
export function checkEvent(raw: Record<string, unknown>, index: EventIndex): EventCheck {
  const errors: EventProblem[] = [];
  const name = typeof raw.event === "string" ? raw.event : typeof raw.eventType === "string" ? raw.eventType : undefined;
  const identity = (isObject(raw.identity) ? raw.identity : {}) as Record<string, unknown>;

  if (!nonBlank(raw.eventId)) errors.push({ field: "eventId", message: "eventId is required" });
  if (!name || !NAME_PATTERN.test(name)) {
    errors.push({ field: "event", message: `event name "${String(name)}" must match ${NAME_PATTERN.source}` });
  }
  if (!nonBlank(raw.timestamp) || !Number.isFinite(Date.parse(String(raw.timestamp)))) {
    errors.push({ field: "timestamp", message: "timestamp must be a valid ISO-8601 date-time" });
  }
  if (!nonBlank(identity.anonymousId)) errors.push({ field: "identity.anonymousId", message: "anonymousId is required" });
  if (!nonBlank(identity.sessionId)) errors.push({ field: "identity.sessionId", message: "sessionId is required" });

  const event = name ? index.find(name) : undefined;
  if (event) {
    const data = (isObject(raw.data) ? raw.data : {}) as Record<string, unknown>;
    const root = { ...data, identity };
    for (const [path, field] of Object.entries(index.fieldsOf(event))) {
      const value = valueAt(root, path);
      const wire = path.startsWith("identity.") ? path : `data.${path}`;
      if (missing(value)) {
        if (field.required) errors.push({ field: wire, message: `${leaf(path)} is required` });
        continue;
      }
      checkValue(wire, leaf(path), value, field, index.vocabularies, errors);
    }
  }
  walkSensitive(raw.data, "data", errors);
  walkSensitive(raw.properties, "properties", errors);

  return { valid: errors.length === 0, errors, event, unplanned: !event && !!name && NAME_PATTERN.test(name) };
}

function checkValue(
  path: string,
  name: string,
  value: unknown,
  field: RuntimeField,
  vocabularies: Record<string, string[]>,
  errors: EventProblem[]
): void {
  const fail = (message: string): void => {
    errors.push({ field: path, message });
  };
  switch (field.type) {
    case "string":
      if (typeof value !== "string") return fail(`${name} must be a string`);
      if (field.maxLength !== undefined && value.length > field.maxLength) fail(`${name} must be at most ${field.maxLength} characters`);
      if (field.pattern !== undefined && !new RegExp(field.pattern).test(value)) {
        fail(field.pattern === "^[A-Z]{3}$" ? `${name} must be a 3-letter ISO 4217 code` : `${name} has an invalid format`);
      }
      return;
    case "timestamp":
      if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) fail(`${name} must be an ISO-8601 date-time`);
      return;
    case "boolean":
      if (typeof value !== "boolean") fail(`${name} must be true or false`);
      return;
    case "enum": {
      const allowed = vocabularies[field.vocabulary ?? ""] ?? [];
      if (typeof value !== "string" || !allowed.includes(value)) fail(`${name} must be one of ${allowed.join(", ")}`);
      return;
    }
    case "integer":
    case "number":
    case "money": {
      const number = typeof value === "number" ? value : field.type === "money" && typeof value === "string" && MONEY.test(value) ? Number(value) : NaN;
      if (!Number.isFinite(number)) return fail(field.type === "money" ? `${name} must be a decimal amount` : `${name} must be a number`);
      if (field.type === "integer" && !Number.isInteger(number)) return fail(`${name} must be a whole number`);
      if (field.minimum !== undefined && number < field.minimum) {
        fail(field.minimum === 0 ? `${name} must be a non-negative number` : field.minimum === 1 ? `${name} must be greater than 0` : `${name} must be at least ${field.minimum}`);
      }
      if (field.maximum !== undefined && number > field.maximum) fail(`${name} must be at most ${field.maximum}`);
      return;
    }
    case "array":
      if (!Array.isArray(value)) return fail(`${name} must be a list`);
      if (field.minItems !== undefined && value.length < field.minItems) fail(`${name} must have at least ${field.minItems} item(s)`);
      if (field.items) value.forEach((item, i) => checkValue(`${path}[${i}]`, name, item, field.items!, vocabularies, errors));
      return;
    case "object":
      if (!isObject(value)) return fail(`${name} must be an object`);
      for (const [key, child] of Object.entries(field.fields ?? {})) {
        const childValue = (value as Record<string, unknown>)[key];
        if (missing(childValue)) {
          if (child.required) errors.push({ field: `${path}.${key}`, message: `${key} is required` });
        } else {
          checkValue(`${path}.${key}`, key, childValue, child, vocabularies, errors);
        }
      }
  }
}

function walkSensitive(value: unknown, path: string, errors: EventProblem[], depth = 0): void {
  if (!isObject(value) && !Array.isArray(value)) return;
  if (depth > 12) return;
  if (Array.isArray(value)) {
    value.forEach((v, i) => walkSensitive(v, `${path}[${i}]`, errors, depth + 1));
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (SENSITIVE.has(key.toLowerCase().replace(/[^a-z0-9]/g, ""))) {
      errors.push({ field: `${path}.${key}`, message: `"${key}" looks like sensitive data and must never be tracked` });
    }
    walkSensitive(child, `${path}.${key}`, errors, depth + 1);
  }
}

function valueAt(root: Record<string, unknown>, path: string): unknown {
  let current: unknown = root;
  for (const part of path.split(".")) {
    if (!isObject(current)) return undefined;
    current = current[part];
  }
  return current;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const nonBlank = (v: unknown) => typeof v === "string" && v.trim().length > 0;
const missing = (v: unknown) =>
  v === undefined || v === null || (typeof v === "string" && v.trim() === "") || (Array.isArray(v) && v.length === 0);
const leaf = (path: string) => path.slice(path.lastIndexOf(".") + 1);
