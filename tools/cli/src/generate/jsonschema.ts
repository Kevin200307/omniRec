// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Catalog } from "../catalog/model";
import { PATHS, type GeneratedFile } from "./types";

/**
 * Emits `schema/commerce-event.schema.json`.
 *
 * Phase 1 keeps the v1 envelope exactly as it was: the hand-written envelope
 * lives in `catalog/envelope/v1.schema.json`, and only the event-name enum is
 * filled in from the catalog. Phase 3 generates the v2 envelope from blocks.
 */
export function generateJsonSchema(catalog: Catalog, repoRoot: string): GeneratedFile {
  const template = JSON.parse(readFileSync(join(repoRoot, PATHS.schemaTemplate), "utf8"));
  const eventType = template?.properties?.eventType;
  if (!eventType || !Array.isArray(eventType.enum)) {
    throw new Error(`${PATHS.schemaTemplate} must define properties.eventType.enum as an array`);
  }
  eventType.enum = catalog.events.map((e) => e.name);
  return { path: PATHS.schema, content: JSON.stringify(template, null, 2) + "\n" };
}
