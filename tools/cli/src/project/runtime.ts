// SPDX-License-Identifier: Apache-2.0
import standard from "../standard/catalog.json";

/**
 * The catalog as runtime data (the generated catalog.json), which is what a
 * user's project sees: the CLI ships a copy, so `omnirec dev` and
 * `omnirec validate` work without the omniRec repository.
 */
export interface RuntimeField {
  type: "string" | "integer" | "number" | "boolean" | "timestamp" | "money" | "enum" | "array" | "object";
  required?: boolean;
  description?: string;
  vocabulary?: string;
  minimum?: number;
  maximum?: number;
  minItems?: number;
  maxLength?: number;
  pattern?: string;
  items?: RuntimeField;
  fields?: Record<string, RuntimeField>;
}

export interface RuntimeEvent {
  name: string;
  domain: string;
  version: number;
  kind: "standard" | "custom";
  control: boolean;
  sources: string[];
  blocks: string[];
  aliases: string[];
  autocapture?: boolean;
  required: string[];
  /** Fields the event declares or refines, by dotted path from data (or identity.*). */
  fields: Record<string, RuntimeField>;
  description?: string;
  example?: Record<string, unknown>;
}

export interface RuntimeCatalog {
  catalogVersion: number;
  schemaVersion: string;
  vocabularies: Record<string, string[]>;
  blocks: Record<string, Record<string, RuntimeField>>;
  events: RuntimeEvent[];
}

export const STANDARD: RuntimeCatalog = standard as unknown as RuntimeCatalog;

export const NAME_PATTERN = /^[a-z][a-z0-9_]{2,63}$/;

/** Catalog plus plan, with name and alias lookup. */
export class EventIndex {
  private readonly byName = new Map<string, RuntimeEvent>();

  constructor(
    readonly catalog: RuntimeCatalog,
    readonly custom: RuntimeEvent[] = [],
    readonly vocabularies: Record<string, string[]> = { ...catalog.vocabularies }
  ) {
    for (const event of [...catalog.events, ...custom]) {
      this.byName.set(event.name, event);
      for (const alias of event.aliases) this.byName.set(alias, event);
    }
  }

  /** The event a name or alias refers to. */
  find(name: string): RuntimeEvent | undefined {
    return this.byName.get(name);
  }

  get events(): RuntimeEvent[] {
    return [...this.catalog.events, ...this.custom];
  }

  /**
   * Every field the event can carry: its blocks' fields at `block.field`,
   * with the event's own declarations and refinements on top.
   */
  fieldsOf(event: RuntimeEvent): Record<string, RuntimeField> {
    const out: Record<string, RuntimeField> = {};
    for (const block of event.blocks) {
      for (const [name, field] of Object.entries(this.catalog.blocks[block] ?? {})) {
        out[`${block}.${name}`] = { ...field, required: false };
      }
    }
    for (const [path, field] of Object.entries(event.fields)) out[path] = { ...out[path], ...field };
    for (const path of event.required) if (out[path]) out[path] = { ...out[path], required: true };
    return out;
  }
}
