// SPDX-License-Identifier: Apache-2.0
import type { Catalog, FieldSpec } from "../catalog/model";
import { GENERATED_NOTICE, PATHS, type GeneratedFile } from "./types";

/** `product_interaction` -> `ProductInteractionEventType`. Matches the names types.ts has always exported. */
export function domainTypeName(domainId: string): string {
  return domainId.split("_").map((p) => p[0].toUpperCase() + p.slice(1)).join("") + "EventType";
}

/** `order` -> `OrderBlock`. */
export function blockTypeName(blockName: string): string {
  return blockName.split("_").map((p) => p[0].toUpperCase() + p.slice(1)).join("") + "Block";
}

/** TypeScript type for a catalog field. */
export function tsType(field: FieldSpec, catalog: Catalog): string {
  switch (field.type) {
    case "string":
    case "timestamp":
      return "string";
    case "integer":
    case "number":
      return "number";
    case "money":
      return "number | string";
    case "boolean":
      return "boolean";
    case "enum": {
      const values = catalog.vocabularies.find((v) => v.name === field.vocabulary)?.values ?? [];
      return values.length ? values.map((v) => JSON.stringify(v.value)).join(" | ") : "string";
    }
    case "array":
      return field.items ? `Array<${tsType(field.items, catalog)}>` : "unknown[]";
    case "object": {
      const entries = Object.entries(field.fields ?? {});
      if (!entries.length) return "Record<string, unknown>";
      return `{ ${entries.map(([n, f]) => `${n}${f.required ? "" : "?"}: ${tsType(f, catalog)}`).join("; ")} }`;
    }
  }
}

/**
 * Emits `catalog.ts` for @omnirec/commerce-web: the event-name list and union,
 * one union per domain, and type-only maps. Everything except EVENT_NAMES and
 * CONTROL_EVENTS is type-level, so it adds nothing to the browser bundle.
 */
export function generateTypeScript(catalog: Catalog): GeneratedFile {
  const lines: string[] = [];
  lines.push("// SPDX-License-Identifier: Apache-2.0");
  lines.push(`// ${GENERATED_NOTICE}`);
  lines.push("");
  lines.push(`/** Version of the catalog these definitions were generated from. */`);
  lines.push(`export const CATALOG_VERSION = ${catalog.catalogVersion};`);
  lines.push("");
  lines.push("/** Every standard event name, ordered by domain then name. */");
  lines.push("export const EVENT_NAMES = [");
  for (const event of catalog.events) lines.push(`  ${JSON.stringify(event.name)},`);
  lines.push("] as const;");
  lines.push("");
  lines.push("export type EventName = (typeof EVENT_NAMES)[number];");
  lines.push("");

  for (const domain of catalog.domains) {
    const names = catalog.events.filter((e) => e.domain === domain.id).map((e) => e.name);
    lines.push(`/** ${domain.title}: ${domain.description.trim().replace(/\s+/g, " ")} */`);
    if (names.length === 0) {
      lines.push(`export type ${domainTypeName(domain.id)} = never;`);
    } else {
      lines.push(`export type ${domainTypeName(domain.id)} =`);
      names.forEach((name, i) => lines.push(`  | ${JSON.stringify(name)}${i === names.length - 1 ? ";" : ""}`));
    }
    lines.push("");
  }

  lines.push("export type EventDomain =");
  catalog.domains.forEach((d, i) => lines.push(`  | ${JSON.stringify(d.id)}${i === catalog.domains.length - 1 ? ";" : ""}`));
  lines.push("");

  lines.push("/** Control events steer the pipeline and are never delivered to providers. */");
  const control = catalog.events.filter((e) => e.control).map((e) => JSON.stringify(e.name));
  lines.push(`export const CONTROL_EVENTS: readonly EventName[] = [${control.join(", ")}];`);
  lines.push("");

  for (const block of catalog.blocks) {
    lines.push(`/** ${block.description.trim().replace(/\s+/g, " ")} */`);
    lines.push(`export interface ${blockTypeName(block.name)} {`);
    for (const [name, field] of Object.entries(block.fields)) {
      if (field.description) lines.push(`  /** ${field.description.trim().replace(/\s+/g, " ")} */`);
      lines.push(`  ${name}?: ${tsType(field, catalog)};`);
    }
    lines.push("}");
    lines.push("");
  }

  lines.push("/** The `data` object of an event: catalog blocks, plus inline fields a tracking plan declares. */");
  lines.push("export interface EventData {");
  for (const block of catalog.blocks) lines.push(`  ${block.name}?: ${blockTypeName(block.name)};`);
  lines.push("  [field: string]: unknown;");
  lines.push("}");
  lines.push("");

  lines.push("/** Domain of each standard event at runtime, for plugins such as consent. Tree-shaken when unused. */");
  lines.push("export const EVENT_DOMAINS: Readonly<Record<EventName, EventDomain>> = {");
  for (const event of catalog.events) lines.push(`  ${event.name}: ${JSON.stringify(event.domain)},`);
  lines.push("};");
  lines.push("");

  lines.push("/** Domain of each event. Type-only. */");
  lines.push("export interface EventDomainMap {");
  for (const event of catalog.events) lines.push(`  ${event.name}: ${JSON.stringify(event.domain)};`);
  lines.push("}");
  lines.push("");

  lines.push("/** Dotted paths each event requires. Type-only; `never` when nothing beyond the envelope is required. */");
  lines.push("export interface EventRequiredPaths {");
  for (const event of catalog.events) {
    const paths = event.required.map((p) => JSON.stringify(p));
    lines.push(`  ${event.name}: ${paths.length ? paths.join(" | ") : "never"};`);
  }
  lines.push("}");
  lines.push("");

  lines.push(...eventDataMap(catalog));
  lines.push(...eventRules(catalog));

  return { path: PATHS.tsCatalog, content: lines.join("\n") };
}

/**
 * The `data` each event takes, for typed `track()`. A block the event requires a
 * field of is mandatory, with that field non-optional; other listed blocks are
 * optional. Events that carry no data take an empty object.
 */
function eventDataMap(catalog: Catalog): string[] {
  const out: string[] = [];
  out.push("/** The `data` each standard event takes. Drives the types of `track()`. */");
  out.push("export interface EventDataMap {");
  for (const event of catalog.events) {
    const parts: string[] = [];
    for (const blockName of event.blocks) {
      const required = event.required.filter((p) => p.startsWith(`${blockName}.`));
      const base = blockTypeName(blockName);
      if (required.length === 0) {
        parts.push(`${blockName}?: ${base}`);
      } else {
        const fields = required.map((path) => {
          const field = event.fields.find((f) => f.path === path)!;
          return `${path.slice(blockName.length + 1)}: ${tsType(field, catalog)}`;
        });
        parts.push(`${blockName}: ${base} & { ${fields.join("; ")} }`);
      }
    }
    for (const field of event.fields.filter((f) => !f.path.includes("."))) {
      parts.push(`${field.path}${field.required ? "" : "?"}: ${tsType(field, catalog)}`);
    }
    out.push(`  ${event.name}: ${parts.length ? `{ ${parts.join("; ")} }` : "Record<string, never>"};`);
  }
  // Aliases are typed like their canonical event, so either name autocompletes.
  for (const event of catalog.events) {
    for (const alias of event.aliases) out.push(`  ${alias}: EventDataMap[${JSON.stringify(event.name)}];`);
  }
  out.push("}");
  out.push("");
  return out;
}

/** Compact runtime rules for the browser's pre-send check. The server validates in full. */
function eventRules(catalog: Catalog): string[] {
  const out: string[] = [];
  out.push("/** A field constraint the browser checks before sending. */");
  out.push("export interface FieldRule {");
  out.push("  min?: number;");
  out.push("  max?: number;");
  out.push("  minItems?: number;");
  out.push("  maxLength?: number;");
  out.push("  pattern?: string;");
  out.push("  values?: readonly string[];");
  out.push("  /** For arrays of objects: required keys and per-key rules of each element. */");
  out.push("  item?: { required: readonly string[]; fields: Readonly<Record<string, FieldRule>> };");
  out.push("}");
  out.push("");
  out.push("export interface EventRule {");
  out.push("  required: readonly string[];");
  out.push("  fields?: Readonly<Record<string, FieldRule>>;");
  out.push("}");
  out.push("");
  out.push("/** Required paths and constraints per standard event, from the catalog. */");

  out.push("");
  out.push("/** Older or alternative names, mapped to the canonical event. The collector rewrites them. */");
  out.push("export const EVENT_ALIASES: Readonly<Record<string, EventName>> = {");
  for (const event of catalog.events) {
    for (const alias of event.aliases) out.push(`  ${alias}: ${JSON.stringify(event.name)},`);
  }
  out.push("};");
  out.push("");
  out.push("");
  out.push("/**");
  out.push(" * Required paths per canonical event, comma-separated: the compact table the");
  out.push(" * browser core checks in production. Full rules (EVENT_RULES) are for the debug");
  out.push(" * plugin and tests; the server always validates in full.");
  out.push(" */");
  out.push("export const REQUIRED_FIELDS: Readonly<Record<EventName, string>> = {");
  for (const event of catalog.events) out.push(`  ${event.name}: ${JSON.stringify(event.required.join(","))},`);
  out.push("};");
  out.push("");
  const marketing = catalog.events.filter((e) => e.domain === "acquisition_messaging").map((e) => JSON.stringify(e.name));
  out.push("/** Events in the acquisition and messaging domain: marketing, for consent purposes. */");
  out.push(`export const MARKETING_EVENTS: readonly EventName[] = [${marketing.join(", ")}];`);
  out.push("");
  out.push("/** Rules by canonical name or alias. A static literal so bundlers drop it when unused. */");
  out.push("export const EVENT_RULES: Readonly<Record<string, EventRule>> = {");
  for (const event of catalog.events) {
    for (const name of [event.name, ...event.aliases]) out.push(`  ${name}: ${JSON.stringify(ruleBody(event, catalog))},`);
  }
  out.push("};");
  out.push("");
  return out;
}

function ruleBody(event: Catalog["events"][number], catalog: Catalog): Record<string, unknown> {
  const fields: Record<string, unknown> = {};
  for (const field of event.fields.filter((f) => f.refined)) {
    const rule = fieldRule(field, catalog);
    if (Object.keys(rule).length) fields[field.path] = rule;
  }
  const body: Record<string, unknown> = { required: event.required };
  if (Object.keys(fields).length) body.fields = fields;
  return body;
}

function fieldRule(field: FieldSpec, catalog: Catalog): Record<string, unknown> {
  const rule: Record<string, unknown> = {};
  if (field.minimum !== undefined) rule.min = field.minimum;
  if (field.maximum !== undefined) rule.max = field.maximum;
  if (field.minItems !== undefined) rule.minItems = field.minItems;
  if (field.maxLength !== undefined) rule.maxLength = field.maxLength;
  if (field.pattern !== undefined) rule.pattern = field.pattern;
  if (field.type === "enum") {
    rule.values = catalog.vocabularies.find((v) => v.name === field.vocabulary)?.values.map((v) => v.value) ?? [];
  }
  if (field.type === "array" && field.items?.type === "object") {
    const entries = Object.entries(field.items.fields ?? {});
    rule.item = {
      required: entries.filter(([, f]) => f.required).map(([n]) => n),
      fields: Object.fromEntries(
        entries.map(([n, f]) => [n, fieldRule(f, catalog)]).filter(([, r]) => Object.keys(r as object).length)
      ),
    };
  }
  return rule;
}
