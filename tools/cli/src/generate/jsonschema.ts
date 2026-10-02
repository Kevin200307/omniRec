// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Catalog, FieldSpec } from "../catalog/model";
import { PATHS, type GeneratedFile } from "./types";

const MONEY_STRING = "^-?[0-9]+(\\.[0-9]+)?$";

/** JSON Schema (draft-07) for one catalog field. */
export function fieldSchema(field: FieldSpec, catalog: Catalog): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  switch (field.type) {
    case "string":
      out.type = "string";
      if (field.maxLength !== undefined) out.maxLength = field.maxLength;
      if (field.pattern !== undefined) out.pattern = field.pattern;
      break;
    case "integer":
    case "number":
      out.type = field.type;
      break;
    case "money":
      // A decimal string keeps exact cents; numbers are accepted for convenience.
      out.type = ["number", "string"];
      out.pattern = MONEY_STRING;
      break;
    case "boolean":
      out.type = "boolean";
      break;
    case "timestamp":
      out.type = "string";
      out.format = "date-time";
      break;
    case "enum":
      out.enum = catalog.vocabularies.find((v) => v.name === field.vocabulary)?.values.map((v) => v.value) ?? [];
      break;
    case "array":
      out.type = "array";
      if (field.items) out.items = fieldSchema(field.items, catalog);
      if (field.minItems !== undefined) out.minItems = field.minItems;
      break;
    case "object": {
      out.type = "object";
      out.additionalProperties = false;
      const fields = Object.entries(field.fields ?? {});
      out.properties = Object.fromEntries(fields.map(([name, child]) => [name, fieldSchema(child, catalog)]));
      const required = fields.filter(([, child]) => child.required).map(([name]) => name);
      if (required.length) out.required = required;
      break;
    }
  }
  if (field.minimum !== undefined) out.minimum = field.minimum;
  if (field.maximum !== undefined) out.maximum = field.maximum;
  if (field.description) out.description = field.description.trim();
  return out;
}

/**
 * Emits `schema/commerce-event.schema.json` (envelope v2) from
 * `catalog/envelope/v2.schema.json`: the standard event names go into
 * `properties.event["x-omnirec-standard-events"]` and every block becomes a
 * typed object under `properties.data.properties`.
 *
 * Event names stay open (pattern, not enum) because tracking plans add custom
 * events; the registry decides at runtime which names a tenant may send.
 */
export function generateJsonSchema(catalog: Catalog, repoRoot: string): GeneratedFile {
  const template = JSON.parse(readFileSync(join(repoRoot, PATHS.schemaTemplate), "utf8"));
  const event = template?.properties?.event;
  const data = template?.properties?.data;
  if (!event || !data || typeof data.properties !== "object") {
    throw new Error(`${PATHS.schemaTemplate} must define properties.event and properties.data.properties`);
  }
  event["x-omnirec-standard-events"] = catalog.events.map((e) => e.name);
  data.properties = Object.fromEntries(
    catalog.blocks.map((block) => [
      block.name,
      fieldSchema({ type: "object", description: block.description, fields: block.fields }, catalog),
    ])
  );
  return { path: PATHS.schema, content: JSON.stringify(template, null, 2) + "\n" };
}
