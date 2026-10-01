// SPDX-License-Identifier: Apache-2.0
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join, relative, sep } from "node:path";
import Ajv, { type ValidateFunction } from "ajv";
import { parseDocument } from "yaml";
import fileSchema from "./catalog-file.schema.json";
import {
  ENVELOPE_FIELDS,
  NAME_PATTERN,
  type BlockDefinition,
  type Catalog,
  type DomainDefinition,
  type EventDefinition,
  type EventSource,
  type FieldConstraints,
  type FieldSpec,
  type ResolvedField,
  type VocabularyDefinition,
} from "./model";

export interface CatalogIssue {
  /** Path relative to the catalog's parent directory, with forward slashes. */
  file: string;
  line?: number;
  message: string;
}

export class CatalogLoadError extends Error {
  constructor(readonly issues: CatalogIssue[]) {
    super(
      `catalog has ${issues.length} problem(s):\n` +
        issues.map((i) => `  ${i.file}${i.line ? `:${i.line}` : ""}  ${i.message}`).join("\n")
    );
    this.name = "CatalogLoadError";
  }
}

const NUMERIC_TYPES = new Set(["integer", "number", "money"]);
const REFINEMENT_ONLY_KEYS: (keyof FieldConstraints)[] = [
  "required",
  "minimum",
  "maximum",
  "minItems",
  "maxLength",
  "pattern",
];

/**
 * Loads and validates the catalog rooted at `catalogDir` (the folder holding
 * `catalog.yaml`). Every problem found is reported at once, rather than one per
 * run, so a contributor fixes a batch of mistakes in one pass.
 *
 * @throws CatalogLoadError listing every problem found.
 */
export function loadCatalog(catalogDir: string): Catalog {
  const ctx = new LoadContext(catalogDir);

  const header = ctx.readFile(join(catalogDir, "catalog.yaml"), "catalogFile") as
    | { catalogVersion: number; schemaVersion: string; domains: DomainDefinition[] }
    | undefined;
  if (!header) throw new CatalogLoadError(ctx.issues);

  const domains = header.domains;
  const domainIds = new Set<string>();
  for (const domain of domains) {
    if (domainIds.has(domain.id)) ctx.issue("catalog.yaml", `domain "${domain.id}" is declared twice`);
    domainIds.add(domain.id);
  }

  const vocabularies = ctx
    .readDir("vocabularies", "vocabularyFile")
    .map(({ data, file }) => ctx.checkFileName(file, data as VocabularyDefinition));
  const vocabularyNames = ctx.uniqueNames(vocabularies, "vocabulary");
  for (const vocab of vocabularies) {
    const seen = new Set<string>();
    for (const { value } of vocab.values) {
      if (seen.has(value)) ctx.issue(ctx.fileOf(vocab), `vocabulary value "${value}" is listed twice`);
      seen.add(value);
    }
  }

  const blocks = ctx.readDir("blocks", "blockFile").map(({ data, file }) => ctx.checkFileName(file, data as BlockDefinition));
  ctx.uniqueNames(blocks, "block");
  for (const block of blocks) {
    for (const [name, field] of Object.entries(block.fields)) {
      ctx.checkFieldSpec(ctx.fileOf(block), `${block.name}.${name}`, field, vocabularyNames);
    }
  }
  const blocksByName = new Map(blocks.map((b) => [b.name, b]));

  const events: EventDefinition[] = [];
  for (const { data, file } of ctx.readDir("events", "eventFile", true)) {
    const raw = ctx.checkFileName(file, data as RawEvent);
    const event = resolveEvent(ctx, raw, file, domainIds, blocksByName, vocabularyNames);
    if (event) events.push(event);
  }

  // Names and aliases share one namespace: an alias that equals another event's
  // name would make the wire name ambiguous.
  const owners = new Map<string, string>();
  for (const event of events) {
    for (const name of [event.name, ...event.aliases]) {
      const owner = owners.get(name);
      if (owner) {
        ctx.issue(event.file, `"${name}" is already used by ${owner}`);
      } else {
        owners.set(name, name === event.name ? `event ${event.name}` : `an alias of ${event.name}`);
      }
    }
  }

  const vocabByName = new Map(vocabularies.map((v) => [v.name, v]));
  for (const event of events) {
    if (event.example) validateExample(ctx, event, vocabByName);
  }

  if (ctx.issues.length > 0) throw new CatalogLoadError(ctx.issues);

  const domainOrder = new Map(domains.map((d, i) => [d.id, i]));
  events.sort((a, b) => domainOrder.get(a.domain)! - domainOrder.get(b.domain)! || a.name.localeCompare(b.name));
  blocks.sort((a, b) => a.name.localeCompare(b.name));
  vocabularies.sort((a, b) => a.name.localeCompare(b.name));

  return {
    catalogVersion: header.catalogVersion,
    schemaVersion: header.schemaVersion,
    domains,
    blocks,
    vocabularies,
    events,
  };
}

interface RawEvent {
  name: string;
  domain: string;
  version: number;
  control?: boolean;
  sources: EventSource[];
  description: string;
  blocks?: string[];
  properties?: Record<string, Partial<FieldSpec>>;
  aliases?: string[];
  autocapture?: boolean;
  example?: Record<string, unknown>;
}

function resolveEvent(
  ctx: LoadContext,
  raw: RawEvent,
  file: string,
  domainIds: Set<string>,
  blocksByName: Map<string, BlockDefinition>,
  vocabularyNames: Set<string>
): EventDefinition | undefined {
  const rel = ctx.rel(file);
  let ok = true;
  const fail = (message: string) => {
    ctx.issue(rel, message);
    ok = false;
  };

  if (!domainIds.has(raw.domain)) fail(`domain "${raw.domain}" is not declared in catalog.yaml`);
  const parentDir = basename(join(file, ".."));
  if (parentDir !== raw.domain) fail(`event is in folder "${parentDir}" but declares domain "${raw.domain}"`);

  const blockNames = raw.blocks ?? [];
  for (const name of blockNames) {
    if (!blocksByName.has(name)) fail(`block "${name}" does not exist`);
  }

  const fields = new Map<string, ResolvedField>();
  for (const name of blockNames) {
    const block = blocksByName.get(name);
    if (!block) continue;
    for (const [fieldName, spec] of Object.entries(block.fields)) {
      const path = `${name}.${fieldName}`;
      fields.set(path, { ...spec, required: false, path, block: name, refined: false });
    }
  }

  const required: string[] = [];
  for (const [key, prop] of Object.entries(raw.properties ?? {})) {
    let resolved: ResolvedField;
    if (key.includes(".")) {
      if (prop.type || prop.vocabulary || prop.items || prop.fields) {
        fail(`"${key}" refines an existing field and may only set constraints, not type, vocabulary, items or fields`);
        continue;
      }
      const envelope = ENVELOPE_FIELDS[key];
      const base: ResolvedField | undefined = envelope ? { ...envelope, path: key, refined: true } : fields.get(key);
      if (!base) {
        const [blockName] = key.split(".");
        fail(
          blockNames.includes(blockName)
            ? `"${key}" is not a field of block "${blockName}"`
            : `"${key}" refines block "${blockName}", which this event does not list under blocks`
        );
        continue;
      }
      resolved = { ...base, ...pickConstraints(prop), refined: true };
    } else {
      if (blockNames.includes(key)) {
        fail(`inline property "${key}" has the same name as a block`);
        continue;
      }
      if (!prop.type) {
        fail(`inline property "${key}" needs a type`);
        continue;
      }
      ctx.checkFieldSpec(rel, key, prop as FieldSpec, vocabularyNames);
      resolved = { ...(prop as FieldSpec), path: key, refined: true };
    }
    const problem = constraintProblem(resolved);
    if (problem) {
      fail(`"${key}": ${problem}`);
      continue;
    }
    fields.set(key, resolved);
    if (resolved.required) required.push(key);
  }

  if (!ok) return undefined;
  return {
    name: raw.name,
    domain: raw.domain,
    version: raw.version,
    kind: "standard",
    control: raw.control ?? false,
    sources: raw.sources,
    description: raw.description.trim(),
    blocks: blockNames,
    aliases: raw.aliases ?? [],
    autocapture: raw.autocapture ?? false,
    fields: [...fields.values()].sort((a, b) => a.path.localeCompare(b.path)),
    required,
    example: raw.example,
    file: rel,
  };
}

function pickConstraints(prop: Partial<FieldSpec>): FieldConstraints {
  const out: FieldConstraints = {};
  for (const key of REFINEMENT_ONLY_KEYS) {
    if (prop[key] !== undefined) (out as Record<string, unknown>)[key] = prop[key];
  }
  if (prop.description !== undefined) (out as Record<string, unknown>).description = prop.description;
  return out;
}

/** Returns a message when a constraint does not fit the field's type. */
function constraintProblem(field: FieldSpec): string | undefined {
  if ((field.minimum !== undefined || field.maximum !== undefined) && !NUMERIC_TYPES.has(field.type)) {
    return `minimum/maximum only apply to integer, number or money, not ${field.type}`;
  }
  if (field.minimum !== undefined && field.maximum !== undefined && field.minimum > field.maximum) {
    return "minimum is greater than maximum";
  }
  if (field.minItems !== undefined && field.type !== "array") {
    return `minItems only applies to arrays, not ${field.type}`;
  }
  if ((field.maxLength !== undefined || field.pattern !== undefined) && field.type !== "string") {
    return `maxLength/pattern only apply to strings, not ${field.type}`;
  }
  if (field.pattern !== undefined) {
    try {
      new RegExp(field.pattern);
    } catch {
      return `pattern ${JSON.stringify(field.pattern)} is not a valid regular expression`;
    }
  }
  return undefined;
}

// --- example validation ---------------------------------------------------

function validateExample(ctx: LoadContext, event: EventDefinition, vocabs: Map<string, VocabularyDefinition>) {
  const example = event.example!;
  const report = (message: string) => ctx.issue(event.file, `example: ${message}`);

  const topLevel = new Set<string>([...event.blocks, "identity"]);
  for (const field of event.fields) if (!field.path.includes(".")) topLevel.add(field.path);
  for (const key of Object.keys(example)) {
    if (!topLevel.has(key)) report(`"${key}" is not a block or property of this event`);
  }

  for (const blockName of event.blocks) {
    const value = example[blockName];
    if (value === undefined) continue;
    if (!isPlainObject(value)) {
      report(`"${blockName}" must be an object`);
      continue;
    }
    const known = new Set(event.fields.filter((f) => f.block === blockName).map((f) => f.path.slice(blockName.length + 1)));
    for (const key of Object.keys(value)) {
      if (!known.has(key)) report(`"${blockName}.${key}" is not a field of block "${blockName}"`);
    }
  }

  for (const path of event.required) {
    if (isMissing(getPath(example, path))) report(`required field "${path}" is missing`);
  }

  for (const field of event.fields) {
    const value = getPath(example, field.path);
    if (value === undefined) continue;
    checkValue(field.path, value, field, vocabs, report);
  }
}

function checkValue(
  path: string,
  value: unknown,
  spec: FieldSpec,
  vocabs: Map<string, VocabularyDefinition>,
  report: (message: string) => void
): void {
  const wrongType = () => report(`"${path}" should be ${spec.type}, got ${JSON.stringify(value)}`);
  switch (spec.type) {
    case "string":
      if (typeof value !== "string") return wrongType();
      if (spec.maxLength !== undefined && value.length > spec.maxLength) report(`"${path}" is longer than ${spec.maxLength}`);
      if (spec.pattern !== undefined && !new RegExp(spec.pattern).test(value)) report(`"${path}" does not match ${spec.pattern}`);
      return;
    case "integer":
      if (typeof value !== "number" || !Number.isInteger(value)) return wrongType();
      break;
    case "number":
      if (typeof value !== "number" || !Number.isFinite(value)) return wrongType();
      break;
    case "money":
      if (!(typeof value === "number" && Number.isFinite(value)) && !(typeof value === "string" && /^-?\d+(\.\d+)?$/.test(value))) {
        return wrongType();
      }
      break;
    case "boolean":
      if (typeof value !== "boolean") wrongType();
      return;
    case "timestamp":
      if (typeof value !== "string" || Number.isNaN(Date.parse(value))) wrongType();
      return;
    case "enum": {
      const allowed = vocabs.get(spec.vocabulary ?? "")?.values.map((v) => v.value) ?? [];
      if (typeof value !== "string" || !allowed.includes(value)) {
        report(`"${path}" must be one of ${spec.vocabulary} (${allowed.join(", ")})`);
      }
      return;
    }
    case "array":
      if (!Array.isArray(value)) return wrongType();
      if (spec.minItems !== undefined && value.length < spec.minItems) report(`"${path}" needs at least ${spec.minItems} item(s)`);
      if (spec.items) value.forEach((item, i) => checkValue(`${path}[${i}]`, item, spec.items!, vocabs, report));
      return;
    case "object":
      if (!isPlainObject(value)) return wrongType();
      for (const [name, child] of Object.entries(spec.fields ?? {})) {
        const childValue = value[name];
        if (child.required && isMissing(childValue)) report(`required field "${path}.${name}" is missing`);
        if (childValue !== undefined) checkValue(`${path}.${name}`, childValue, child, vocabs, report);
      }
      return;
  }
  const n = Number(value);
  if (spec.minimum !== undefined && n < spec.minimum) report(`"${path}" is below the minimum ${spec.minimum}`);
  if (spec.maximum !== undefined && n > spec.maximum) report(`"${path}" is above the maximum ${spec.maximum}`);
}

function getPath(root: Record<string, unknown>, path: string): unknown {
  let current: unknown = root;
  for (const part of path.split(".")) {
    if (!isPlainObject(current)) return undefined;
    current = current[part];
  }
  return current;
}

function isMissing(value: unknown): boolean {
  return value === undefined || value === null || value === "" || (Array.isArray(value) && value.length === 0);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// --- file handling ----------------------------------------------------------

class LoadContext {
  readonly issues: CatalogIssue[] = [];
  private readonly validators = new Map<string, ValidateFunction>();
  private readonly files = new WeakMap<object, string>();
  private readonly base: string;

  constructor(private readonly catalogDir: string) {
    this.base = join(catalogDir, "..");
    const ajv = new Ajv({ allErrors: true, strict: false });
    ajv.addSchema(fileSchema);
    for (const def of ["catalogFile", "blockFile", "vocabularyFile", "eventFile"]) {
      this.validators.set(def, ajv.getSchema(`${fileSchema.$id}#/definitions/${def}`)!);
    }
  }

  rel(file: string): string {
    return relative(this.base, file).split(sep).join("/");
  }

  issue(file: string, message: string, line?: number) {
    this.issues.push({ file, message, ...(line ? { line } : {}) });
  }

  fileOf(item: object): string {
    return this.files.get(item) ?? "catalog";
  }

  /** Reads and schema-checks one YAML file. Returns undefined when it is unusable. */
  readFile(file: string, definition: string): unknown {
    const rel = this.rel(file);
    if (!existsSync(file)) {
      this.issue(rel, "file not found");
      return undefined;
    }
    const doc = parseDocument(readFileSync(file, "utf8"));
    if (doc.errors.length > 0) {
      for (const error of doc.errors) this.issue(rel, `YAML: ${error.message.split("\n")[0]}`, error.linePos?.[0]?.line);
      return undefined;
    }
    const data = doc.toJS();
    const validate = this.validators.get(definition)!;
    if (!validate(data)) {
      for (const error of validate.errors ?? []) {
        const where = error.instancePath || "(root)";
        const extra =
          error.keyword === "additionalProperties"
            ? ` "${(error.params as { additionalProperty: string }).additionalProperty}"`
            : "";
        this.issue(rel, `${where} ${error.message}${extra}`);
      }
      return undefined;
    }
    if (data && typeof data === "object") this.files.set(data, rel);
    return data;
  }

  /** Reads every *.yaml under catalog/<sub>, optionally one folder deep. Sorted for determinism. */
  readDir(sub: string, definition: string, nested = false): { data: unknown; file: string }[] {
    const dir = join(this.catalogDir, sub);
    if (!existsSync(dir)) return [];
    const out: { data: unknown; file: string }[] = [];
    for (const entry of readdirSync(dir).sort()) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) {
        if (nested) {
          for (const child of readdirSync(path).sort()) {
            if (child.endsWith(".yaml")) this.pushFile(join(path, child), definition, out);
            else if (child.endsWith(".yml")) this.issue(this.rel(join(path, child)), "use the .yaml extension");
          }
        } else {
          this.issue(this.rel(path), `unexpected folder in catalog/${sub}`);
        }
      } else if (entry.endsWith(".yaml")) {
        if (nested) this.issue(this.rel(path), `event files belong in catalog/${sub}/<domain>/`);
        else this.pushFile(path, definition, out);
      } else if (entry.endsWith(".yml")) {
        this.issue(this.rel(path), "use the .yaml extension");
      }
    }
    return out;
  }

  private pushFile(file: string, definition: string, out: { data: unknown; file: string }[]) {
    const data = this.readFile(file, definition);
    if (data !== undefined) out.push({ data, file });
  }

  checkFileName<T extends { name: string }>(file: string, data: T): T {
    if (basename(file, ".yaml") !== data.name) {
      this.issue(this.rel(file), `file name must match name "${data.name}" (expected ${data.name}.yaml)`);
    }
    if (!NAME_PATTERN.test(data.name)) this.issue(this.rel(file), `name "${data.name}" does not match ${NAME_PATTERN}`);
    this.files.set(data, this.rel(file));
    return data;
  }

  uniqueNames(items: { name: string }[], kind: string): Set<string> {
    const names = new Set<string>();
    for (const item of items) {
      if (names.has(item.name)) this.issue(this.fileOf(item), `${kind} "${item.name}" is defined twice`);
      names.add(item.name);
    }
    return names;
  }

  checkFieldSpec(file: string, path: string, spec: FieldSpec, vocabularies: Set<string>) {
    if (spec.type === "enum") {
      if (!spec.vocabulary) this.issue(file, `"${path}" is an enum and needs a vocabulary`);
      else if (!vocabularies.has(spec.vocabulary)) this.issue(file, `"${path}" uses vocabulary "${spec.vocabulary}", which does not exist`);
    } else if (spec.vocabulary) {
      this.issue(file, `"${path}" sets a vocabulary but is not an enum`);
    }
    if (spec.type === "array" && !spec.items) this.issue(file, `"${path}" is an array and needs items`);
    if (spec.type !== "array" && spec.items) this.issue(file, `"${path}" sets items but is not an array`);
    if (spec.type !== "object" && spec.fields) this.issue(file, `"${path}" sets fields but is not an object`);
    const problem = constraintProblem(spec);
    if (problem) this.issue(file, `"${path}": ${problem}`);
    if (spec.items) this.checkFieldSpec(file, `${path}[]`, spec.items, vocabularies);
    for (const [name, child] of Object.entries(spec.fields ?? {})) {
      this.checkFieldSpec(file, `${path}.${name}`, child, vocabularies);
    }
  }
}
