// SPDX-License-Identifier: Apache-2.0
/**
 * In-memory model of the event catalog (`catalog/` at the repository root).
 *
 * The YAML files are the source of truth. The loader validates them and
 * resolves block references, producing a {@link Catalog} that every generator
 * reads. Generators never look at YAML directly.
 */

export type FieldType =
  | "string"
  | "integer"
  | "number"
  | "boolean"
  | "timestamp"
  | "money"
  | "enum"
  | "array"
  | "object";

/** Event sources: where an event is expected to be produced. */
export type EventSource = "browser" | "server" | "webhook" | "derived" | "import";

export const EVENT_SOURCES: readonly EventSource[] = ["browser", "server", "webhook", "derived", "import"];

/** Constraints that may appear on a field, or refine a block field from an event. */
export interface FieldConstraints {
  required?: boolean;
  minimum?: number;
  maximum?: number;
  minItems?: number;
  maxLength?: number;
  pattern?: string;
}

export interface FieldSpec extends FieldConstraints {
  type: FieldType;
  description?: string;
  /** For `enum`: the vocabulary whose values are allowed. */
  vocabulary?: string;
  /** For `array`: the element definition. */
  items?: FieldSpec;
  /** For `object`: nested fields. */
  fields?: Record<string, FieldSpec>;
}

export interface DomainDefinition {
  id: string;
  title: string;
  description: string;
}

export interface BlockDefinition {
  name: string;
  description: string;
  fields: Record<string, FieldSpec>;
}

export interface VocabularyValue {
  value: string;
  description?: string;
}

export interface VocabularyDefinition {
  name: string;
  description: string;
  values: VocabularyValue[];
}

/**
 * A field as an event sees it after resolution: the block's definition with
 * the event's refinements merged on top. `path` is dotted from the event root,
 * for example `commerce.productId` or `identity.userId`.
 */
export interface ResolvedField extends FieldSpec {
  path: string;
  /** Block the field came from, or undefined for inline and envelope fields. */
  block?: string;
  /** True when the event's own properties declare or refine this field. */
  refined: boolean;
}

export interface EventDefinition {
  name: string;
  domain: string;
  version: number;
  /** "standard" for catalog events. Tracking plans add "custom" in Phase 4. */
  kind: "standard" | "custom";
  /** Control events steer the pipeline and are never delivered to providers. */
  control: boolean;
  sources: EventSource[];
  description: string;
  blocks: string[];
  aliases: string[];
  autocapture: boolean;
  /** Fields the event refines or declares, keyed by dotted path. Sorted by path. */
  fields: ResolvedField[];
  /** Dotted paths that must be present, in definition order. */
  required: string[];
  example?: Record<string, unknown>;
  /** Repository-relative path of the YAML file, for error messages. */
  file: string;
}

export interface Catalog {
  catalogVersion: number;
  schemaVersion: string;
  domains: DomainDefinition[];
  blocks: BlockDefinition[];
  vocabularies: VocabularyDefinition[];
  /** Ordered by domain declaration order, then by name. */
  events: EventDefinition[];
}

/** Paths outside any block that an event may refine. */
export const ENVELOPE_FIELDS: Readonly<Record<string, FieldSpec>> = {
  "identity.userId": {
    type: "string",
    description: "Merchant's own customer id, supplied via identify().",
  },
};

export const NAME_PATTERN = /^[a-z][a-z0-9_]{2,63}$/;
