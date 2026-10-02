// SPDX-License-Identifier: Apache-2.0
import type { Catalog } from "../catalog/model";
import { runtimeCatalogJson } from "./runtime-json";
import { GENERATED_NOTICE, PATHS, type GeneratedFile } from "./types";

export function javaConstantName(eventName: string): string {
  return eventName.toUpperCase();
}

function javadoc(text: string): string {
  return text.trim().replace(/\s+/g, " ").replace(/\*\//g, "*&#47;");
}

/**
 * Emits `StandardEvents.java` (string constants for every standard event) and
 * the runtime catalog resource that the Java event registry loads in Phase 2.
 */
export function generateJava(catalog: Catalog): GeneratedFile[] {
  const lines: string[] = [];
  lines.push("// SPDX-License-Identifier: Apache-2.0");
  lines.push(`// ${GENERATED_NOTICE}`);
  lines.push("package io.omnirec.commerce.catalog.generated;");
  lines.push("");
  lines.push("import java.util.List;");
  lines.push("import java.util.Set;");
  lines.push("");
  lines.push("/**");
  lines.push(" * Wire names of every standard event in the OmniRec catalog.");
  lines.push(" *");
  lines.push(" * <p>Use these instead of string literals so a renamed or removed event is a");
  lines.push(" * compile error. The full definitions, including required fields, are in");
  lines.push(` * the classpath resource {@value #CATALOG_RESOURCE}.`);
  lines.push(" */");
  lines.push("public final class StandardEvents {");
  lines.push("");
  lines.push("    /** Version of the catalog these constants were generated from. */");
  lines.push(`    public static final int CATALOG_VERSION = ${catalog.catalogVersion};`);
  lines.push("");
  lines.push("    /** Classpath location of the runtime catalog. */");
  lines.push(`    public static final String CATALOG_RESOURCE = "omnirec/catalog.json";`);

  for (const domain of catalog.domains) {
    const events = catalog.events.filter((e) => e.domain === domain.id);
    if (events.length === 0) continue;
    lines.push("");
    lines.push(`    // --- ${domain.title} (${domain.id}) ---`);
    for (const event of events) {
      lines.push("");
      lines.push(`    /** ${javadoc(event.description)} */`);
      lines.push(`    public static final String ${javaConstantName(event.name)} = ${JSON.stringify(event.name)};`);
    }
  }

  lines.push("");
  lines.push("    /** Every standard event name, ordered by domain then name. */");
  lines.push("    public static final List<String> ALL = List.of(");
  catalog.events.forEach((e, i) => {
    lines.push(`            ${javaConstantName(e.name)}${i === catalog.events.length - 1 ? ");" : ","}`);
  });
  lines.push("");
  lines.push("    /** Control events steer the pipeline and are never delivered to providers. */");
  const control = catalog.events.filter((e) => e.control).map((e) => javaConstantName(e.name));
  lines.push(`    public static final Set<String> CONTROL = Set.of(${control.join(", ")});`);
  lines.push("");
  lines.push("    private StandardEvents() {");
  lines.push("    }");
  lines.push("}");
  lines.push("");

  return [
    { path: PATHS.javaStandardEvents, content: lines.join("\n") },
    { path: PATHS.javaStandardEventNames, content: generateEventNames(catalog) },
    { path: PATHS.javaCatalogJson, content: runtimeCatalogJson(catalog) },
  ];
}

/**
 * `StandardEventNames`: the same names as typed {@code EventName} values, for
 * code that compares or builds events. `StandardEvents` keeps the plain strings
 * because annotations need compile-time constants.
 */
function generateEventNames(catalog: Catalog): string {
  const lines: string[] = [];
  lines.push("// SPDX-License-Identifier: Apache-2.0");
  lines.push(`// ${GENERATED_NOTICE}`);
  lines.push("package io.omnirec.commerce.catalog.generated;");
  lines.push("");
  lines.push("import io.omnirec.commerce.model.EventName;");
  lines.push("");
  lines.push("/** Every standard event as a typed {@link EventName}. String forms are in {@link StandardEvents}. */");
  lines.push("public final class StandardEventNames {");
  for (const event of catalog.events) {
    const constant = javaConstantName(event.name);
    lines.push("");
    lines.push(`    /** ${javadoc(event.description)} */`);
    lines.push(`    public static final EventName ${constant} = EventName.of(StandardEvents.${constant});`);
  }
  lines.push("");
  lines.push("    private StandardEventNames() {");
  lines.push("    }");
  lines.push("}");
  lines.push("");
  return lines.join("\n");
}
