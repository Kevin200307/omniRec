// SPDX-License-Identifier: Apache-2.0
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { checkEvent, indexWithPlan, parsePlan, runProjectGenerate, STANDARD } from "../src";

const PLAN = `
vocabularies:
  size_unit: [cm, inch]
events:
  size_guide_opened:
    description: "Shopper opened the size guide."
    blocks: [product]
    aliases: [size_chart_opened]
    properties:
      product.id: { required: true }
      unit: { type: enum, vocabulary: size_unit }
  quiz_completed:
    properties:
      score: { type: integer, required: true }
      answers: { type: array, items: { type: object, fields: { question: { type: string, required: true } } } }
`;

function project(files: Record<string, string>) {
  const dir = mkdtempSync(join(tmpdir(), "omnirec-project-"));
  for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), content);
  return dir;
}

function generate(cwd: string, check = false) {
  const lines: string[] = [];
  const code = runProjectGenerate({ cwd, check, log: (l) => lines.push(l) });
  return { code, out: lines.join("\n") };
}

describe("omnirec generate in a project", () => {
  it("writes TypeScript declarations that augment OmnirecCustomEvents", () => {
    const cwd = project({ "package.json": "{}", "omnirec.plan.yaml": PLAN });
    expect(generate(cwd).code).toBe(0);
    const dts = readFileSync(join(cwd, "omnirec.d.ts"), "utf8");
    expect(dts).toContain('declare module "@omnirec/commerce-web"');
    expect(dts).toContain("interface OmnirecCustomEvents");
    expect(dts).toContain('unit?: "cm" | "inch"');
    expect(dts).toContain("product: { ");
    expect(dts).toMatch(/product: \{ [^}]*\bid: string/);
    expect(dts).toContain("score: number");
    expect(dts).toContain("answers?: Array<{ question: string }>");
    expect(dts).toContain('size_chart_opened: OmnirecCustomEvents["size_guide_opened"]');
    expect(existsSync(join(cwd, "src"))).toBe(false);
  });

  it("writes Java constants in a Maven or Gradle project", () => {
    const cwd = project({ "pom.xml": "<project/>", "omnirec.plan.yaml": PLAN });
    expect(generate(cwd).code).toBe(0);
    const java = readFileSync(join(cwd, "src/main/java/omnirec/OmnirecEvents.java"), "utf8");
    expect(java).toContain("package omnirec;");
    expect(java).toContain('public static final String SIZE_GUIDE_OPENED = "size_guide_opened";');
    expect(existsSync(join(cwd, "omnirec.d.ts"))).toBe(false);
  });

  it("--check reports drift without writing", () => {
    const cwd = project({ "package.json": "{}", "omnirec.plan.yaml": PLAN });
    expect(generate(cwd, true).code).toBe(1);
    expect(existsSync(join(cwd, "omnirec.d.ts"))).toBe(false);
    generate(cwd);
    expect(generate(cwd, true).code).toBe(0);
    writeFileSync(join(cwd, "omnirec.plan.yaml"), PLAN.replace("[cm, inch]", "[cm, inch, eu]"));
    expect(generate(cwd, true).code).toBe(1);
  });

  it("reports plan problems and a missing plan", () => {
    expect(generate(project({ "package.json": "{}" })).code).toBe(2);
    const bad = generate(project({ "package.json": "{}", "omnirec.plan.yaml": "events:\n  product_viewed: {}\n" }));
    expect(bad.code).toBe(1);
    expect(bad.out).toContain("standard catalog event");
  });
});

describe("the CLI's validator agrees with the catalog", () => {
  const index = indexWithPlan(parsePlan(PLAN, "plan"));
  const envelope = (name: string, data: Record<string, unknown> = {}) => {
    const { identity, ...rest } = data as { identity?: Record<string, unknown> };
    return {
      eventId: "e1",
      event: name,
      timestamp: "2026-10-01T12:00:00Z",
      identity: { anonymousId: "a1", sessionId: "s1", ...identity },
      data: rest,
    };
  };

  it("accepts every catalog example and enforces every required field", () => {
    for (const event of STANDARD.events) {
      const ok = checkEvent(envelope(event.name, event.example ?? {}), index);
      expect(ok.errors, event.name).toEqual([]);
      for (const path of event.required) {
        const copy = structuredClone(event.example ?? {}) as Record<string, unknown>;
        const parts = path.split(".");
        let node = copy as Record<string, unknown>;
        for (const part of parts.slice(0, -1)) node = (node[part] ?? {}) as Record<string, unknown>;
        delete node[parts[parts.length - 1]];
        const result = checkEvent(envelope(event.name, copy), index);
        const wire = path.startsWith("identity.") ? path : `data.${path}`;
        expect(result.errors.map((e) => e.field), `${event.name} without ${path}`).toContain(wire);
      }
    }
  });

  it("checks plan events, nested items and aliases", () => {
    expect(checkEvent(envelope("size_chart_opened", { product: { id: "P1" }, unit: "cm" }), index).valid).toBe(true);
    expect(checkEvent(envelope("size_guide_opened", { product: { id: "P1" }, unit: "mm" }), index).errors[0].field).toBe("data.unit");
    expect(checkEvent(envelope("quiz_completed", { score: 1, answers: [{}] }), index).errors.map((e) => e.field)).toEqual([
      "data.answers[0].question",
    ]);
    expect(checkEvent(envelope("quiz_completed", { score: 1.5 }), index).errors[0].message).toContain("whole number");
  });
});
