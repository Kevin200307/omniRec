// SPDX-License-Identifier: Apache-2.0
/**
 * Reads `data-omnirec-*` attributes into event data, shared by the dom and
 * impressions plugins.
 *
 * Attributes on the element itself win; then each ancestor is read in turn,
 * nearest first, and only fills fields not already set. So a product card can
 * declare `data-omnirec-product="P100"` once and every button inside inherits
 * it, while a button can still override any field.
 */

/** Attributes that steer the plugins rather than carry data. Never inherited. */
const CONTROL = new Set(["event", "on", "impression", "page"]);

type Kind = "string" | "integer" | "money";

/** Attribute name (after `data-omnirec-`) -> where it goes in `data`, and its type. */
const MAPPED: Record<string, [string, Kind]> = {
  product: ["product.id", "string"],
  "product-id": ["product.id", "string"],
  "product-name": ["product.name", "string"],
  variant: ["product.variantId", "string"],
  brand: ["product.brand", "string"],
  price: ["product.price", "money"],
  currency: ["product.currency", "string"],
  quantity: ["product.quantity", "integer"],
  category: ["category.id", "string"],
  "category-name": ["category.name", "string"],
  list: ["list.id", "string"],
  "list-name": ["list.name", "string"],
  position: ["list.position", "integer"],
  search: ["search.query", "string"],
  "search-query": ["search.query", "string"],
  cart: ["cart.id", "string"],
  order: ["order.id", "string"],
  recommendation: ["recommendation.id", "string"],
  "recommendation-provider": ["recommendation.provider", "string"],
};

export interface CollectedFields {
  data: Record<string, unknown>;
  properties: Record<string, unknown>;
  /** Attribute problems, for example invalid JSON. Reported, never thrown. */
  problems: string[];
}

const PREFIX = "data-omnirec-";

function camel(name: string): string {
  return name.replace(/-([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}

function setPath(target: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.split(".");
  let current = target;
  for (let i = 0; i < parts.length - 1; i++) {
    const next = current[parts[i]];
    if (next === null || typeof next !== "object" || Array.isArray(next)) current[parts[i]] = {};
    current = current[parts[i]] as Record<string, unknown>;
  }
  current[parts[parts.length - 1]] = value;
}

function hasPath(target: Record<string, unknown>, path: string): boolean {
  let current: unknown = target;
  for (const part of path.split(".")) {
    if (current === null || typeof current !== "object") return false;
    if (!(part in (current as Record<string, unknown>))) return false;
    current = (current as Record<string, unknown>)[part];
  }
  return true;
}

function coerce(value: string, kind: Kind): unknown {
  if (kind === "integer") {
    const n = Number(value);
    return Number.isInteger(n) ? n : value; // leave it for the validator to report
  }
  return value; // money stays a decimal string, exactly as written
}

/** Unknown attributes: plain values, with obvious booleans and numbers converted. */
function coerceLoose(value: string): unknown {
  if (value === "true") return true;
  if (value === "false") return false;
  if (/^-?(0|[1-9]\d*)(\.\d+)?$/.test(value)) return Number(value);
  return value;
}

function mergeMissing(target: Record<string, unknown>, source: Record<string, unknown>, prefix = ""): void {
  for (const [key, value] of Object.entries(source)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value !== null && typeof value === "object" && !Array.isArray(value)) {
      mergeMissing(target, value as Record<string, unknown>, path);
    } else if (!hasPath(target, path)) {
      setPath(target, path, value);
    }
  }
}

function parseJson(raw: string, attribute: string, problems: string[]): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
    problems.push(`${attribute} must be a JSON object`);
  } catch {
    problems.push(`${attribute} is not valid JSON`);
  }
  return undefined;
}

/** Fields declared on one element, without inheritance. */
function ownFields(element: Element, problems: string[]): CollectedFields {
  const data: Record<string, unknown> = {};
  const properties: Record<string, unknown> = {};
  for (const attribute of Array.from(element.attributes)) {
    if (!attribute.name.startsWith(PREFIX)) continue;
    const name = attribute.name.slice(PREFIX.length);
    if (CONTROL.has(name)) continue;
    if (name === "props") {
      const json = parseJson(attribute.value, attribute.name, problems);
      if (json) Object.assign(properties, json);
    } else if (name === "data") {
      const json = parseJson(attribute.value, attribute.name, problems);
      if (json) mergeMissing(data, json);
    } else if (MAPPED[name]) {
      const [path, kind] = MAPPED[name];
      if (!hasPath(data, path)) setPath(data, path, coerce(attribute.value, kind));
    } else {
      const key = camel(name);
      if (!(key in data)) data[key] = coerceLoose(attribute.value);
    }
  }
  return { data, properties, problems };
}

/** Fields for an event fired from `element`: its own, then inherited from ancestors. */
export function collectFields(element: Element): CollectedFields {
  const problems: string[] = [];
  const data: Record<string, unknown> = {};
  const properties: Record<string, unknown> = {};
  for (let node: Element | null = element; node; node = node.parentElement) {
    const own = ownFields(node, problems);
    mergeMissing(data, own.data);
    for (const [key, value] of Object.entries(own.properties)) {
      if (!(key in properties)) properties[key] = value;
    }
  }
  return { data, properties, problems };
}
