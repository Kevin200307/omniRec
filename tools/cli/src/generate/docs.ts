// SPDX-License-Identifier: Apache-2.0
import type { Catalog, EventDefinition, FieldSpec, ResolvedField } from "../catalog/model";
import { GENERATED_NOTICE, PATHS, type GeneratedFile } from "./types";

const cell = (text: string) => text.replace(/\|/g, "\\|").replace(/\s+/g, " ").trim();

function constraintText(field: FieldSpec): string {
  const parts: string[] = [];
  if (field.minimum !== undefined) parts.push(`min ${field.minimum}`);
  if (field.maximum !== undefined) parts.push(`max ${field.maximum}`);
  if (field.minItems !== undefined) parts.push(`at least ${field.minItems} item(s)`);
  if (field.maxLength !== undefined) parts.push(`max length ${field.maxLength}`);
  if (field.pattern !== undefined) parts.push(`pattern \`${field.pattern}\``);
  if (field.vocabulary) parts.push(`one of \`${field.vocabulary}\``);
  return parts.join(", ");
}

function typeText(field: FieldSpec): string {
  if (field.type === "array" && field.items) return `array of ${field.items.type}`;
  return field.type;
}

function eventSection(event: EventDefinition): string[] {
  const out: string[] = [];
  out.push(`## \`${event.name}\``);
  out.push("");
  out.push(event.description.trim());
  out.push("");
  const facts = [
    `**Sources:** ${event.sources.join(", ")}`,
    `**Version:** ${event.version}`,
  ];
  if (event.control) facts.push("**Control event:** not delivered to providers");
  if (event.autocapture) facts.push("**Autocaptured**");
  if (event.aliases.length) facts.push(`**Aliases:** ${event.aliases.map((a) => `\`${a}\``).join(", ")}`);
  out.push(facts.join(" · "));
  out.push("");

  const shown: ResolvedField[] = event.fields.filter((f) => f.refined);
  if (shown.length === 0) {
    out.push("No fields are required beyond the envelope.");
  } else {
    out.push("| Field | Type | Required | Constraints |");
    out.push("|---|---|---|---|");
    for (const field of shown) {
      out.push(
        `| \`${field.path}\` | ${typeText(field)} | ${field.required ? "yes" : "no"} | ${cell(constraintText(field))} |`
      );
    }
  }
  if (event.blocks.length) {
    out.push("");
    out.push(`Other fields come from the ${event.blocks.map((b) => `\`${b}\``).join(", ")} block.`);
  }
  if (event.example) {
    out.push("");
    out.push("```json");
    out.push(JSON.stringify(event.example, null, 2));
    out.push("```");
  }
  out.push("");
  return out;
}

/** Emits `docs/events/README.md` and one page per domain. */
export function generateDocs(catalog: Catalog): GeneratedFile[] {
  const files: GeneratedFile[] = [];

  const index: string[] = [];
  index.push(`<!-- ${GENERATED_NOTICE} -->`);
  index.push("");
  index.push("# Event catalog");
  index.push("");
  index.push(
    `Catalog version ${catalog.catalogVersion}, envelope schema ${catalog.schemaVersion}, ${catalog.events.length} events.`
  );
  index.push("");
  index.push("| Domain | Events | Description |");
  index.push("|---|---|---|");
  for (const domain of catalog.domains) {
    const count = catalog.events.filter((e) => e.domain === domain.id).length;
    index.push(`| [${domain.title}](${domain.id}.md) | ${count} | ${cell(domain.description)} |`);
  }
  index.push("");
  index.push("## Blocks");
  index.push("");
  for (const block of catalog.blocks) {
    index.push(`### \`${block.name}\``);
    index.push("");
    index.push(block.description.trim());
    index.push("");
    index.push("| Field | Type | Constraints | Description |");
    index.push("|---|---|---|---|");
    for (const [name, field] of Object.entries(block.fields)) {
      index.push(`| \`${name}\` | ${typeText(field)} | ${cell(constraintText(field))} | ${cell(field.description ?? "")} |`);
    }
    index.push("");
  }
  if (catalog.vocabularies.length) {
    index.push("## Vocabularies");
    index.push("");
    for (const vocab of catalog.vocabularies) {
      index.push(`- **\`${vocab.name}\`**: ${vocab.values.map((v) => `\`${v.value}\``).join(", ")}`);
    }
    index.push("");
  }
  files.push({ path: `${PATHS.docsDir}/README.md`, content: index.join("\n") });

  for (const domain of catalog.domains) {
    const events = catalog.events.filter((e) => e.domain === domain.id);
    const page: string[] = [];
    page.push(`<!-- ${GENERATED_NOTICE} -->`);
    page.push("");
    page.push(`# ${domain.title}`);
    page.push("");
    page.push(domain.description.trim());
    page.push("");
    page.push(`[All domains](README.md)`);
    page.push("");
    for (const event of events) page.push(...eventSection(event));
    files.push({ path: `${PATHS.docsDir}/${domain.id}.md`, content: page.join("\n") });
  }
  return files;
}
