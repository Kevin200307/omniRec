// SPDX-License-Identifier: Apache-2.0
import { existsSync, readFileSync } from "node:fs";
import { parse } from "yaml";
import { EventIndex, NAME_PATTERN, STANDARD, type RuntimeCatalog, type RuntimeEvent, type RuntimeField } from "./runtime";

/**
 * Tracking plans (`omnirec.plan.yaml`): a store's custom events on top of the
 * standard catalog. The rules here match the collector's PlanLoader, so a
 * plan `omnirec validate` accepts is one the collector loads.
 */
export interface Plan {
  events: RuntimeEvent[];
  vocabularies: Record<string, string[]>;
}

export class PlanError extends Error {
  constructor(readonly problems: string[]) {
    super(problems.join("\n"));
  }
}

const FIELD_NAME = /^[a-z][A-Za-z0-9]*$/;
const TYPES = new Set(["string", "integer", "number", "boolean", "timestamp", "money", "enum", "array", "object"]);
const EVENT_KEYS = new Set(["domain", "version", "sources", "description", "blocks", "properties", "aliases", "example"]);
const FIELD_KEYS = new Set(["type", "description", "vocabulary", "items", "fields", "required", "minimum", "maximum", "minItems", "maxLength", "pattern"]);
const CONSTRAINT_KEYS = new Set(["required", "minimum", "maximum", "minItems", "maxLength", "pattern", "description"]);
const RESERVED = new Set(["identity", "context", "properties"]);
const SOURCES = new Set(["browser", "server", "webhook", "derived", "import"]);

type Yaml = Record<string, unknown>;
const isMap = (v: unknown): v is Yaml => typeof v === "object" && v !== null && !Array.isArray(v);
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.map(String) : []);

/** Reads and checks a plan file. Throws {@link PlanError} listing every problem. */
export function loadPlanFile(path: string, catalog: RuntimeCatalog = STANDARD): Plan {
  if (!existsSync(path)) throw new PlanError([`${path}: file not found`]);
  return parsePlan(readFileSync(path, "utf8"), path, catalog);
}

/** Checks plan text. `label` names the source in messages. */
export function parsePlan(text: string, label: string, catalog: RuntimeCatalog = STANDARD): Plan {
  let doc: unknown;
  try {
    doc = parse(text) ?? {};
  } catch (error) {
    throw new PlanError([`${label}: ${(error as Error).message.split("\n")[0]}`]);
  }
  if (!isMap(doc)) throw new PlanError([`${label}: a plan must be a YAML mapping`]);

  const problems: string[] = [];
  for (const key of Object.keys(doc)) {
    if (key !== "events" && key !== "vocabularies") {
      problems.push(`${label}: unknown top-level key "${key}" (expected events, vocabularies)`);
    }
  }

  const vocabularies: Record<string, string[]> = {};
  for (const [name, values] of Object.entries(isMap(doc.vocabularies) ? doc.vocabularies : {})) {
    const where = `${label}: vocabulary ${name}`;
    if (!NAME_PATTERN.test(name)) problems.push(`${where}: name must match ${NAME_PATTERN.source}`);
    else if (catalog.vocabularies[name]) problems.push(`${where}: already defined`);
    const list = (Array.isArray(values) ? values : []).map((v) => (isMap(v) ? String(v.value) : String(v)));
    if (list.length === 0) problems.push(`${where}: needs at least one value`);
    vocabularies[name] = list;
  }

  const standard = new EventIndex(catalog);
  const events: RuntimeEvent[] = [];
  for (const [name, node] of Object.entries(isMap(doc.events) ? doc.events : {})) {
    const where = `${label}: event ${name}`;
    if (!NAME_PATTERN.test(name)) {
      problems.push(`${where}: name must match ${NAME_PATTERN.source}`);
      continue;
    }
    if (standard.find(name)) {
      problems.push(`${where}: is a standard catalog event; custom events need their own name`);
      continue;
    }
    const event = readEvent(name, node, where, catalog, vocabularies, problems);
    if (event) events.push(event);
  }

  if (problems.length) throw new PlanError(problems);
  return { events, vocabularies };
}

function readEvent(
  name: string,
  node: unknown,
  where: string,
  catalog: RuntimeCatalog,
  planVocabularies: Record<string, string[]>,
  problems: string[]
): RuntimeEvent | undefined {
  const before = problems.length;
  if (!isMap(node)) {
    problems.push(`${where}: must be a mapping`);
    return undefined;
  }
  for (const key of Object.keys(node)) if (!EVENT_KEYS.has(key)) problems.push(`${where}: unknown key "${key}"`);

  const domain = node.domain === undefined ? "custom" : String(node.domain);
  if (!NAME_PATTERN.test(domain)) problems.push(`${where}: domain must match ${NAME_PATTERN.source}`);
  let sources = strings(node.sources);
  if (sources.length === 0) sources = ["browser", "server"];
  for (const source of sources) if (!SOURCES.has(source)) problems.push(`${where}: unknown source "${source}"`);
  const blocks = strings(node.blocks);
  for (const block of blocks) if (!catalog.blocks[block]) problems.push(`${where}: block "${block}" does not exist`);

  const fields: Record<string, RuntimeField> = {};
  const required: string[] = [];
  for (const [path, rawSpec] of Object.entries(isMap(node.properties) ? node.properties : {})) {
    const at = `${where}: property ${path}`;
    const spec = isMap(rawSpec) ? rawSpec : {};
    for (const key of Object.keys(spec)) if (!FIELD_KEYS.has(key)) problems.push(`${at}: unknown key "${key}"`);

    let field: RuntimeField;
    if (path.includes(".")) {
      const base = refinedBase(path, blocks, catalog, at, problems);
      if (!base) continue;
      for (const key of Object.keys(spec)) {
        if (!CONSTRAINT_KEYS.has(key)) problems.push(`${at}: refines an existing field and may only set constraints, not ${key}`);
      }
      field = { ...base, ...pick(spec, ["required", "minimum", "maximum", "minItems", "maxLength", "pattern", "description"]) };
    } else {
      if (!FIELD_NAME.test(path)) {
        problems.push(`${at}: field names are lowerCamelCase`);
        continue;
      }
      if (RESERVED.has(path) || blocks.includes(path) || catalog.blocks[path]) {
        problems.push(`${at}: "${path}" is reserved for a block or the envelope`);
        continue;
      }
      if (spec.type === undefined) {
        problems.push(`${at}: inline fields need a type`);
        continue;
      }
      field = parseField(spec);
      checkField(field, at, catalog, planVocabularies, problems);
    }
    constraintProblem(field, at, problems);
    fields[path] = field;
    if (field.required) required.push(path);
  }

  if (problems.length > before) return undefined;
  const version = node.version === undefined ? 1 : Number(node.version);
  return {
    name,
    domain,
    version: Number.isInteger(version) && version > 0 ? version : 1,
    kind: "custom",
    control: false,
    sources,
    blocks,
    aliases: strings(node.aliases),
    required,
    fields,
    ...(node.description ? { description: String(node.description) } : {}),
    ...(isMap(node.example) ? { example: node.example } : {}),
  };
}

function refinedBase(path: string, blocks: string[], catalog: RuntimeCatalog, at: string, problems: string[]) {
  if (path === "identity.userId") return { type: "string" } as RuntimeField;
  const [block, field] = [path.slice(0, path.indexOf(".")), path.slice(path.indexOf(".") + 1)];
  if (!blocks.includes(block)) {
    problems.push(`${at}: refines block "${block}", which this event does not list under blocks`);
    return undefined;
  }
  const base = catalog.blocks[block]?.[field];
  if (!base) problems.push(`${at}: "${field}" is not a field of block "${block}"`);
  return base;
}

function parseField(spec: Yaml): RuntimeField {
  const field: RuntimeField = { type: String(spec.type) as RuntimeField["type"] };
  Object.assign(field, pick(spec, ["required", "description", "vocabulary", "minimum", "maximum", "minItems", "maxLength", "pattern"]));
  if (isMap(spec.items)) field.items = parseField(spec.items);
  if (isMap(spec.fields)) {
    field.fields = Object.fromEntries(Object.entries(spec.fields).map(([k, v]) => [k, parseField(isMap(v) ? v : {})]));
  }
  return field;
}

function checkField(field: RuntimeField, at: string, catalog: RuntimeCatalog, plan: Record<string, string[]>, problems: string[]): void {
  if (!TYPES.has(field.type)) {
    problems.push(`${at}: unknown type "${field.type}"`);
    return;
  }
  if (field.type === "enum") {
    if (!field.vocabulary) problems.push(`${at}: enum fields need a vocabulary`);
    else if (!plan[field.vocabulary] && !catalog.vocabularies[field.vocabulary]) {
      problems.push(`${at}: vocabulary "${field.vocabulary}" does not exist`);
    }
  }
  if (field.type === "array" && !field.items) problems.push(`${at}: arrays need items`);
  if (field.items) checkField(field.items, `${at}[]`, catalog, plan, problems);
  for (const [name, child] of Object.entries(field.fields ?? {})) checkField(child, `${at}.${name}`, catalog, plan, problems);
}

function constraintProblem(field: RuntimeField, at: string, problems: string[]): void {
  const numeric = field.type === "integer" || field.type === "number" || field.type === "money";
  if ((field.minimum !== undefined || field.maximum !== undefined) && !numeric) {
    problems.push(`${at}: minimum/maximum only apply to integer, number or money`);
  }
  if (field.minimum !== undefined && field.maximum !== undefined && field.minimum > field.maximum) {
    problems.push(`${at}: minimum is greater than maximum`);
  }
  if (field.minItems !== undefined && field.type !== "array") problems.push(`${at}: minItems only applies to arrays`);
  if ((field.maxLength !== undefined || field.pattern !== undefined) && field.type !== "string") {
    problems.push(`${at}: maxLength/pattern only apply to strings`);
  }
  if (field.pattern !== undefined) {
    try {
      new RegExp(field.pattern);
    } catch {
      problems.push(`${at}: pattern is not a valid regular expression`);
    }
  }
}

function pick(spec: Yaml, keys: string[]): Partial<RuntimeField> {
  const out: Record<string, unknown> = {};
  for (const key of keys) if (spec[key] !== undefined) out[key] = spec[key];
  return out as Partial<RuntimeField>;
}

/** The standard catalog plus a plan, ready for lookups and validation. */
export function indexWithPlan(plan: Plan | undefined, catalog: RuntimeCatalog = STANDARD): EventIndex {
  return new EventIndex(catalog, plan?.events ?? [], { ...catalog.vocabularies, ...(plan?.vocabularies ?? {}) });
}
