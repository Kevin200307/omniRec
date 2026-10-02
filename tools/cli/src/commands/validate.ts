// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { diffPlans, unapproved, type PlanChange } from "../project/diff";
import { parsePlan, PlanError, type Plan } from "../project/plan";

export interface ValidateOptions {
  plan?: string;
  /** A plan file, or a git ref whose version of the plan to compare with. */
  against?: string;
  cwd?: string;
  log?: (line: string) => void;
}

/**
 * `omnirec validate`: lints the plan with the collector's rules. With
 * `--against`, also reports what changed and exits 1 on a breaking change
 * that is not covered by raising the event's `version`.
 *
 * Exit codes: 0 fine, 1 problems or unapproved breaking changes, 2 usage.
 */
export function runValidate(options: ValidateOptions = {}): number {
  const log = options.log ?? ((line) => console.log(line));
  const cwd = resolve(options.cwd ?? process.cwd());
  const planPath = resolve(cwd, options.plan ?? "omnirec.plan.yaml");
  if (!existsSync(planPath)) {
    log(`omnirec validate: ${planPath} not found.`);
    return 2;
  }

  let current: Plan;
  try {
    current = parsePlan(readFileSync(planPath, "utf8"), basename(planPath));
  } catch (error) {
    if (!(error instanceof PlanError)) throw error;
    log(`omnirec validate: ${error.problems.length} problem(s):`);
    for (const problem of error.problems) log(`  ${problem}`);
    return 1;
  }
  log(`omnirec validate: ${basename(planPath)} is valid (${current.events.length} custom event(s)).`);
  if (!options.against) return 0;

  let previousText: string;
  let label: string;
  const againstFile = resolve(cwd, options.against);
  if (existsSync(againstFile)) {
    previousText = readFileSync(againstFile, "utf8");
    label = options.against;
  } else {
    try {
      previousText = gitShow(options.against, planPath);
    } catch (error) {
      log(`omnirec validate: --against ${options.against} is neither a file nor a git ref with this plan (${(error as Error).message.split("\n")[0]})`);
      return 2;
    }
    label = `${options.against}:${basename(planPath)}`;
  }

  let previous: Plan;
  try {
    previous = parsePlan(previousText, label);
  } catch (error) {
    if (!(error instanceof PlanError)) throw error;
    log(`omnirec validate: the plan at ${label} does not load, so it cannot be compared:`);
    for (const problem of error.problems) log(`  ${problem}`);
    return 2;
  }

  const changes = diffPlans(previous, current);
  const blocking = unapproved(changes);
  if (changes.length === 0) {
    log(`No changes against ${label}.`);
    return 0;
  }
  log(`Changes against ${label}:`);
  for (const change of changes) log(`  ${marker(change, blocking)} ${change.message}`);
  if (blocking.length) {
    log("");
    log(`${blocking.length} breaking change(s). Events already sent under the old definition would be rejected or misread.`);
    log("Raise the event's `version` to accept a breaking change, or make it additive (new optional fields, new events).");
    return 1;
  }
  return 0;
}

function marker(change: PlanChange, blocking: PlanChange[]): string {
  if (blocking.includes(change)) return "BREAKING";
  if (change.breaking) return "breaking (version raised)";
  return "ok      ";
}

/** The plan as committed at `ref`. A plan that did not exist yet compares as empty. */
function gitShow(ref: string, planPath: string): string {
  const dir = dirname(planPath);
  execFileSync("git", ["rev-parse", "--verify", `${ref}^{commit}`], { cwd: dir, stdio: "pipe" });
  try {
    return execFileSync("git", ["show", `${ref}:./${basename(planPath)}`], { cwd: dir, stdio: "pipe", encoding: "utf8" });
  } catch {
    return "";
  }
}
