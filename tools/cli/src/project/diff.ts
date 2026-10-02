// SPDX-License-Identifier: Apache-2.0
import type { Plan } from "./plan";
import type { RuntimeEvent, RuntimeField } from "./runtime";

export type ChangeKind =
  | "event-removed"
  | "field-removed"
  | "field-now-required"
  | "type-changed"
  | "vocabulary-value-removed"
  | "event-added"
  | "field-added"
  | "field-now-optional"
  | "vocabulary-value-added";

export interface PlanChange {
  kind: ChangeKind;
  /** Breaks consumers of the event: stored data, dashboards, destinations. */
  breaking: boolean;
  event?: string;
  field?: string;
  vocabulary?: string;
  value?: string;
  /** A breaking change is allowed when the event's version was raised. */
  versionBumped: boolean;
  message: string;
}

const BREAKING = new Set<ChangeKind>(["event-removed", "field-removed", "field-now-required", "type-changed", "vocabulary-value-removed"]);

/**
 * What changed from `before` to `after`. Removing an event, removing a field,
 * making a field required, changing a type and removing a vocabulary value are
 * breaking: events valid under the old plan would be rejected, or mean
 * something else. A breaking change to an event is accepted when its
 * `version` was raised; removing an event never is.
 */
export function diffPlans(before: Plan, after: Plan): PlanChange[] {
  const changes: PlanChange[] = [];
  const oldEvents = new Map(before.events.map((e) => [e.name, e]));
  const newEvents = new Map(after.events.map((e) => [e.name, e]));
  const add = (change: Omit<PlanChange, "breaking">) => changes.push({ ...change, breaking: BREAKING.has(change.kind) });

  for (const [name, old] of oldEvents) {
    const next = newEvents.get(name);
    if (!next) {
      add({ kind: "event-removed", event: name, versionBumped: false, message: `event ${name} was removed` });
      continue;
    }
    const bumped = next.version > old.version;
    const oldFields = flatten(old);
    const newFields = flatten(next);
    for (const [path, field] of oldFields) {
      const now = newFields.get(path);
      if (!now) {
        add({ kind: "field-removed", event: name, field: path, versionBumped: bumped, message: `${name}: field ${path} was removed` });
        continue;
      }
      if (now.type !== field.type) {
        add({ kind: "type-changed", event: name, field: path, versionBumped: bumped,
          message: `${name}: field ${path} changed type from ${field.type} to ${now.type}` });
      }
      if (now.required && !field.required) {
        add({ kind: "field-now-required", event: name, field: path, versionBumped: bumped, message: `${name}: field ${path} is now required` });
      }
      if (!now.required && field.required) {
        add({ kind: "field-now-optional", event: name, field: path, versionBumped: bumped, message: `${name}: field ${path} is now optional` });
      }
    }
    for (const [path, field] of newFields) {
      if (oldFields.has(path)) continue;
      if (field.required) {
        add({ kind: "field-now-required", event: name, field: path, versionBumped: bumped, message: `${name}: new required field ${path}` });
      } else {
        add({ kind: "field-added", event: name, field: path, versionBumped: bumped, message: `${name}: new optional field ${path}` });
      }
    }
  }
  for (const name of newEvents.keys()) {
    if (!oldEvents.has(name)) add({ kind: "event-added", event: name, versionBumped: false, message: `event ${name} was added` });
  }

  for (const [vocabulary, values] of Object.entries(before.vocabularies)) {
    const now = new Set(after.vocabularies[vocabulary] ?? []);
    const users = after.events.filter((e) => usesVocabulary(e, vocabulary));
    const bumped = users.length > 0 && users.every((e) => e.version > (oldEvents.get(e.name)?.version ?? 0));
    for (const value of values) {
      if (!now.has(value)) {
        add({ kind: "vocabulary-value-removed", vocabulary, value, versionBumped: bumped,
          message: `vocabulary ${vocabulary}: value "${value}" was removed` });
      }
    }
  }
  for (const [vocabulary, values] of Object.entries(after.vocabularies)) {
    const was = new Set(before.vocabularies[vocabulary] ?? []);
    for (const value of values) {
      if (!was.has(value) && before.vocabularies[vocabulary]) {
        add({ kind: "vocabulary-value-added", vocabulary, value, versionBumped: false,
          message: `vocabulary ${vocabulary}: value "${value}" was added` });
      }
    }
  }
  return changes;
}

/** Breaking changes that are not covered by a version bump. */
export function unapproved(changes: PlanChange[]): PlanChange[] {
  return changes.filter((c) => c.breaking && !(c.versionBumped && c.kind !== "event-removed"));
}

/** Every declared field, nested object fields and array items included, by path. */
function flatten(event: RuntimeEvent): Map<string, RuntimeField> {
  const out = new Map<string, RuntimeField>();
  const visit = (path: string, field: RuntimeField) => {
    out.set(path, field);
    for (const [name, child] of Object.entries(field.fields ?? {})) visit(`${path}.${name}`, child);
    if (field.items) visit(`${path}[]`, field.items);
  };
  for (const [path, field] of Object.entries(event.fields)) visit(path, field);
  return out;
}

function usesVocabulary(event: RuntimeEvent, vocabulary: string): boolean {
  return [...flatten(event).values()].some((f) => f.vocabulary === vocabulary);
}
