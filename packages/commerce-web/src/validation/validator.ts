// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent } from "../events/types";
import { REQUIRED_FIELDS, type EventRule, type FieldRule } from "../events/generated/catalog";

export interface ValidationError {
  field: string;
  message: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  /** The event name is not in the catalog the SDK was built with. The server decides whether to accept it. */
  unknownEvent?: boolean;
}

/**
 * Field names that must never appear in an event, at any depth, under any
 * casing. This is a hard backstop, not advice: a merchant who accidentally
 * spreads a whole checkout form into `properties` should have the event
 * rejected rather than have a PAN end up in a provider's training data.
 *
 * Matching is on a normalised name (lowercased, separators stripped), so
 * `card_number`, `cardNumber`, and `CardNumber` are all the same key.
 */
const FORBIDDEN_FIELDS = [
  "cardnumber",
  "cardno",
  "pan",
  "cvv",
  "cvc",
  "cvv2",
  "securitycode",
  "cardsecuritycode",
  "expirymonth",
  "expiryyear",
  "cardexpiry",
  "password",
  "passwd",
  "pin",
  "ssn",
  "socialsecuritynumber",
  "accesstoken",
  "refreshtoken",
  "apikey",
  "apisecret",
  "secretkey",
  "privatekey",
  "authorization",
  "creditcard",
  "iban",
];

const NAME = /^[a-z][a-z0-9_]{2,63}$/;

/**
 * Pre-send check. Runs in the browser so a developer sees a mistake in their
 * console; the Event API runs the full catalog validation because a client-side
 * check is a convenience, never a guarantee.
 *
 * Per-event rules come from the generated catalog. By default only required
 * fields are checked, from a compact table, to keep the browser bundle small;
 * pass the full `EVENT_RULES` (the debug plugin does) to also check
 * constraints such as minimum quantities and currency formats. The server
 * always validates in full. Events the catalog does not know — custom events
 * from a tracking plan — pass here and are checked by the server against the
 * tenant's plan.
 */
export class EventValidator {
  constructor(private readonly rules?: Readonly<Record<string, EventRule>>) {}

  private ruleFor(name: string): EventRule | undefined {
    if (this.rules) return this.rules[name];
    // Canonical names only: aliases are typed at compile time, checked by the
    // debug plugin in development and by the server always. Keeping the alias
    // table out of the core keeps it inside its 10 KB budget.
    const required = (REQUIRED_FIELDS as Record<string, string>)[name];
    if (required === undefined) return undefined;
    return { required: required ? required.split(",") : [] };
  }

  validate(event: CommerceEvent): ValidationResult {
    const errors: ValidationError[] = [];

    this.validateUniversal(event, errors);
    const rule = this.ruleFor(event.event);
    if (rule) checkRule(rule, event, errors);
    this.assertNoSensitiveFields(event, errors);

    return { valid: errors.length === 0, errors, ...(rule || !NAME.test(event.event) ? {} : { unknownEvent: true }) };
  }

  private validateUniversal(event: CommerceEvent, errors: ValidationError[]): void {
    if (!isNonEmptyString(event.eventId)) {
      errors.push({ field: "eventId", message: "eventId is required" });
    }
    if (typeof event.event !== "string" || !NAME.test(event.event)) {
      errors.push({ field: "event", message: `event name "${String(event.event)}" must match ${NAME.source}` });
    }
    if (!isNonEmptyString(event.schemaVersion)) {
      errors.push({ field: "schemaVersion", message: "schemaVersion is required" });
    }
    if (!isValidTimestamp(event.timestamp)) {
      errors.push({ field: "timestamp", message: "timestamp must be a valid ISO-8601 date-time" });
    }
    if (!isNonEmptyString(event.identity?.anonymousId)) {
      errors.push({ field: "identity.anonymousId", message: "anonymousId is required" });
    }
    if (!isNonEmptyString(event.identity?.sessionId)) {
      errors.push({ field: "identity.sessionId", message: "sessionId is required" });
    }
  }

  /**
   * Walks data and properties. Cost is bounded by payload size, which the API
   * caps anyway, and the alternative — checking only the top level — would miss
   * the realistic accident of nesting a form object one level down.
   */
  private assertNoSensitiveFields(event: CommerceEvent, errors: ValidationError[]): void {
    const seen = new Set<unknown>();

    const walk = (value: unknown, path: string, depth: number): void => {
      if (value === null || typeof value !== "object" || depth > 12) return;
      if (seen.has(value)) return;
      seen.add(value);

      if (Array.isArray(value)) {
        value.forEach((entry, index) => walk(entry, `${path}[${index}]`, depth + 1));
        return;
      }

      for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
        const childPath = path ? `${path}.${key}` : key;
        if (FORBIDDEN_FIELDS.includes(normaliseKey(key))) {
          errors.push({
            field: childPath,
            message: `"${key}" looks like sensitive data and must never be tracked`,
          });
        }
        walk(entry, childPath, depth + 1);
      }
    };

    walk(event.data, "data", 0);
    walk(event.properties, "properties", 0);
  }
}

/** Catalog paths are relative to data, except identity.*. Errors use the wire path. */
function wirePath(path: string): string {
  return path.startsWith("identity.") ? path : `data.${path}`;
}

function valueAt(event: CommerceEvent, path: string): unknown {
  let current: unknown = path.startsWith("identity.") ? event : event.data;
  for (const part of path.split(".")) {
    if (current === null || typeof current !== "object") return undefined;
    current = (current as Record<string, unknown>)[part];
  }
  return current;
}

function isMissing(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === "string" && value.trim() === "") ||
    (Array.isArray(value) && value.length === 0)
  );
}

function leaf(path: string): string {
  return path.slice(path.lastIndexOf(".") + 1);
}

function checkRule(rule: EventRule, event: CommerceEvent, errors: ValidationError[]): void {
  for (const path of rule.required) {
    if (!isMissing(valueAt(event, path))) continue;
    const isArray = rule.fields?.[path]?.minItems !== undefined || rule.fields?.[path]?.item !== undefined;
    errors.push({
      field: wirePath(path),
      message:
        path === "identity.userId"
          ? "userId is required for this event type"
          : `${leaf(path)} is required${isArray ? " and must be non-empty" : ""}`,
    });
  }
  for (const [path, fieldRule] of Object.entries(rule.fields ?? {})) {
    const value = valueAt(event, path);
    if (!isMissing(value)) checkField(wirePath(path), leaf(path), value, fieldRule, errors);
  }
}

function checkField(path: string, name: string, value: unknown, rule: FieldRule, errors: ValidationError[]): void {
  if (rule.min !== undefined || rule.max !== undefined) {
    const number = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
    if (!Number.isFinite(number)) {
      errors.push({ field: path, message: `${name} must be a number` });
      return;
    }
    if (rule.min !== undefined && number < rule.min) {
      errors.push({
        field: path,
        message:
          rule.min === 0
            ? `${name} must be a non-negative number`
            : rule.min === 1
              ? `${name} must be greater than 0`
              : `${name} must be at least ${rule.min}`,
      });
    }
    if (rule.max !== undefined && number > rule.max) errors.push({ field: path, message: `${name} must be at most ${rule.max}` });
  }
  if (typeof value === "string") {
    if (rule.maxLength !== undefined && value.length > rule.maxLength) {
      errors.push({ field: path, message: `${name} must be at most ${rule.maxLength} characters` });
    }
    if (rule.pattern !== undefined && !new RegExp(rule.pattern).test(value)) {
      errors.push({
        field: path,
        message: rule.pattern === "^[A-Z]{3}$" ? `${name} must be a 3-letter ISO 4217 code` : `${name} has an invalid format`,
      });
    }
    if (rule.values && !rule.values.includes(value)) {
      errors.push({ field: path, message: `${name} must be one of ${rule.values.join(", ")}` });
    }
  }
  if (Array.isArray(value)) {
    if (rule.minItems !== undefined && value.length < rule.minItems) {
      errors.push({ field: path, message: `${name} must have at least ${rule.minItems} item(s)` });
    }
    if (rule.item) {
      value.forEach((element, index) => {
        const line = (element ?? {}) as Record<string, unknown>;
        for (const key of rule.item!.required) {
          if (isMissing(line[key])) errors.push({ field: `${path}[${index}].${key}`, message: `${key} is required` });
        }
        for (const [key, child] of Object.entries(rule.item!.fields)) {
          if (!isMissing(line[key])) checkField(`${path}[${index}].${key}`, key, line[key], child, errors);
        }
      });
    }
  }
}

function normaliseKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isValidTimestamp(value: unknown): boolean {
  if (typeof value !== "string" || value.trim().length === 0) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed);
}

export const SENSITIVE_FIELD_NAMES = FORBIDDEN_FIELDS;
