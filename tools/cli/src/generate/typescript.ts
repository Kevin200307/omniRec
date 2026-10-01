// SPDX-License-Identifier: Apache-2.0
import type { Catalog } from "../catalog/model";
import { GENERATED_NOTICE, PATHS, type GeneratedFile } from "./types";

/** `product_interaction` -> `ProductInteractionEventType`. Matches the names types.ts has always exported. */
export function domainTypeName(domainId: string): string {
  return domainId.split("_").map((p) => p[0].toUpperCase() + p.slice(1)).join("") + "EventType";
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

  return { path: PATHS.tsCatalog, content: lines.join("\n") };
}
