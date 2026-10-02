// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { diffPlans, parsePlan, PlanError, runValidate, unapproved, type ChangeKind } from "../src";

const BASE = `
vocabularies:
  variant: [a, b, c]
events:
  banner_clicked:
    blocks: [product]
    properties:
      product.id: { required: true }
      variant: { type: enum, vocabulary: variant }
      position: { type: integer, minimum: 1 }
  quiz_completed:
    properties:
      score: { type: integer, required: true }
`;

const plan = (text: string) => parsePlan(text, "test");
const kinds = (before: string, after: string) => diffPlans(plan(before), plan(after)).map((c) => [c.kind, c.breaking] as [ChangeKind, boolean]);

describe("plan linting", () => {
  it("accepts a valid plan and resolves blocks, inline fields and vocabularies", () => {
    const p = plan(BASE);
    expect(p.events.map((e) => e.name)).toEqual(["banner_clicked", "quiz_completed"]);
    expect(p.events[0].required).toEqual(["product.id"]);
    expect(p.vocabularies.variant).toEqual(["a", "b", "c"]);
  });

  it.each([
    ["events:\n  Bad Name: {}", "name must match"],
    ["events:\n  product_viewed: {}", "is a standard catalog event"],
    ["events:\n  x_clicked:\n    blocks: [nope]", 'block "nope" does not exist'],
    ["events:\n  x_clicked:\n    properties:\n      product.id: { required: true }", "does not list under blocks"],
    ["events:\n  x_clicked:\n    blocks: [product]\n    properties:\n      product.colourz: { required: true }", 'is not a field of block "product"'],
    ["events:\n  x_clicked:\n    blocks: [product]\n    properties:\n      product.id: { type: integer }", "may only set constraints"],
    ["events:\n  x_clicked:\n    properties:\n      score: { required: true }", "inline fields need a type"],
    ["events:\n  x_clicked:\n    properties:\n      Score: { type: integer }", "lowerCamelCase"],
    ["events:\n  x_clicked:\n    properties:\n      tone: { type: enum, vocabulary: missing }", 'vocabulary "missing" does not exist'],
    ["events:\n  x_clicked:\n    properties:\n      name: { type: string, minimum: 1 }", "minimum/maximum only apply"],
    ["events:\n  x_clicked:\n    sources: [fax]", 'unknown source "fax"'],
    ["events:\n  x_clicked:\n    colour: red", 'unknown key "colour"'],
    ["vocabularies:\n  payment_method: [x]", "already defined"],
    ["other: 1", 'unknown top-level key "other"'],
  ])("rejects %j", (text, message) => {
    try {
      plan(text);
      throw new Error("expected a PlanError");
    } catch (error) {
      expect(error).toBeInstanceOf(PlanError);
      expect((error as PlanError).problems.join("\n")).toContain(message);
    }
  });
});

describe("breaking-change detection", () => {
  it("finds nothing when nothing changed", () => {
    expect(diffPlans(plan(BASE), plan(BASE))).toEqual([]);
  });

  it("removing an event is breaking, even with a version bump", () => {
    const after = BASE.replace(/  quiz_completed:[\s\S]*$/, "");
    const changes = diffPlans(plan(BASE), plan(after));
    expect(changes.map((c) => c.kind)).toEqual(["event-removed"]);
    expect(unapproved(changes)).toHaveLength(1);
  });

  it("removing a field is breaking", () => {
    expect(kinds(BASE, BASE.replace("      position: { type: integer, minimum: 1 }\n", ""))).toEqual([["field-removed", true]]);
  });

  it("making a field required is breaking, and a new required field too", () => {
    expect(kinds(BASE, BASE.replace("variant: { type: enum, vocabulary: variant }", "variant: { type: enum, vocabulary: variant, required: true }")))
      .toEqual([["field-now-required", true]]);
    expect(kinds(BASE, BASE.replace("      score: { type: integer, required: true }", "      score: { type: integer, required: true }\n      level: { type: string, required: true }")))
      .toEqual([["field-now-required", true]]);
  });

  it("changing a type is breaking", () => {
    expect(kinds(BASE, BASE.replace("score: { type: integer", "score: { type: string"))).toEqual([["type-changed", true]]);
  });

  it("removing a vocabulary value is breaking", () => {
    expect(kinds(BASE, BASE.replace("[a, b, c]", "[a, b]"))).toEqual([["vocabulary-value-removed", true]]);
  });

  it("additive changes are not breaking", () => {
    const after = BASE.replace("[a, b, c]", "[a, b, c, d]")
      .replace("score: { type: integer, required: true }", "score: { type: integer }")
      .replace("      position:", "      label: { type: string }\n      position:")
      .concat("  survey_opened:\n    properties:\n      surveyId: { type: string }\n");
    const changes = diffPlans(plan(BASE), plan(after));
    expect(changes.map((c) => c.kind).sort()).toEqual(["event-added", "field-added", "field-now-optional", "vocabulary-value-added"]);
    expect(unapproved(changes)).toEqual([]);
  });

  it("a version bump approves a breaking change to that event", () => {
    const after = BASE.replace("  quiz_completed:\n", "  quiz_completed:\n    version: 2\n").replace("score: { type: integer", "score: { type: string");
    const changes = diffPlans(plan(BASE), plan(after));
    expect(changes.map((c) => c.kind)).toEqual(["type-changed"]);
    expect(unapproved(changes)).toEqual([]);
  });

  it("a vocabulary value removal is approved when every event using it was bumped", () => {
    const after = BASE.replace("[a, b, c]", "[a, b]").replace("  banner_clicked:\n", "  banner_clicked:\n    version: 2\n");
    expect(unapproved(diffPlans(plan(BASE), plan(after)))).toEqual([]);
  });
});

describe("omnirec validate", () => {
  function dir(): string {
    return mkdtempSync(join(tmpdir(), "omnirec-validate-"));
  }

  function run(cwd: string, against?: string) {
    const lines: string[] = [];
    const code = runValidate({ cwd, against, log: (l) => lines.push(l) });
    return { code, out: lines.join("\n") };
  }

  it("exits 0 for a valid plan, 1 for problems, 2 when there is no plan", () => {
    const cwd = dir();
    expect(run(cwd).code).toBe(2);
    writeFileSync(join(cwd, "omnirec.plan.yaml"), BASE);
    expect(run(cwd).code).toBe(0);
    writeFileSync(join(cwd, "omnirec.plan.yaml"), "events:\n  product_viewed: {}\n");
    const bad = run(cwd);
    expect(bad.code).toBe(1);
    expect(bad.out).toContain("standard catalog event");
  });

  it("compares against another file and fails on unapproved breaking changes", () => {
    const cwd = dir();
    writeFileSync(join(cwd, "old.plan.yaml"), BASE);
    writeFileSync(join(cwd, "omnirec.plan.yaml"), BASE.replace("[a, b, c]", "[a, b]"));
    const result = run(cwd, "old.plan.yaml");
    expect(result.code).toBe(1);
    expect(result.out).toContain('BREAKING vocabulary variant: value "c" was removed');
  });

  it("compares against a git ref", () => {
    const cwd = dir();
    const git = (...args: string[]) => execFileSync("git", args, { cwd, stdio: "pipe" });
    git("init", "-q");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "test");
    git("config", "commit.gpgsign", "false");
    writeFileSync(join(cwd, "omnirec.plan.yaml"), BASE);
    git("add", ".");
    git("commit", "-qm", "plan");

    writeFileSync(join(cwd, "omnirec.plan.yaml"), BASE + "  survey_opened: {}\n");
    expect(run(cwd, "HEAD").code).toBe(0);

    writeFileSync(join(cwd, "omnirec.plan.yaml"), BASE.replace("score: { type: integer", "score: { type: number"));
    const result = run(cwd, "HEAD");
    expect(result.code).toBe(1);
    expect(result.out).toContain("quiz_completed: field score changed type from integer to number");

    expect(run(cwd, "no-such-ref").code).toBe(2);
  });
});
