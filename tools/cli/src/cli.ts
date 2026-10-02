#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { startDevServer } from "./commands/dev";
import { findRepoRoot, runGenerate } from "./commands/generate";
import { runInit, type Framework } from "./commands/init";
import { runProjectGenerate } from "./commands/project-generate";
import { runValidate } from "./commands/validate";

const HELP = `omnirec <command> [options]

Commands:
  init                Write a starter omnirec.plan.yaml and print setup for this project
    --force           Replace an existing plan
    --framework <f>   next | react | vue | web | spring | html (default: detected)
    --endpoint <url>  Collector URL for the snippet (default: http://localhost:8124)

  dev                 Local collector that validates events against the catalog and your plan
    --port <n>        Default 8124
    --plan <file>     Default omnirec.plan.yaml, when present

  validate            Check omnirec.plan.yaml
    --plan <file>
    --against <ref>   Also report changes against a file or git ref; exit 1 on unapproved breaking changes

  generate            In your project: omnirec.d.ts and/or OmnirecEvents.java from the plan.
                      In the omniRec repository: SDK types, schema and docs from catalog/.
    --check           Report out-of-date files and exit 1 instead of writing
    --plan <file>     Use project mode with this plan
    --java-package <p> Package of OmnirecEvents (default: omnirec)
    --root <dir>      omniRec repository root (repository mode)

  help                Show this message
`;

type Flags = Record<string, string | boolean>;

function parse(rest: string[], valued: string[], booleans: string[]): Flags | string {
  const flags: Flags = {};
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i];
    const name = arg.replace(/^--/, "");
    if (booleans.includes(name)) flags[name] = true;
    else if (valued.includes(name)) {
      const value = rest[++i];
      if (value === undefined) return `option ${arg} needs a value`;
      flags[name] = value;
    } else return `unknown option ${arg}`;
  }
  return flags;
}

async function main(argv: string[]): Promise<number> {
  const [command, ...rest] = argv;
  if (!command || command === "help" || command === "--help" || command === "-h") {
    console.log(HELP);
    return command ? 0 : 2;
  }
  const usage = (message: string) => {
    console.error(`omnirec ${command}: ${message}\n\n${HELP}`);
    return 2;
  };

  if (command === "generate") {
    const flags = parse(rest, ["root", "plan", "java-package"], ["check"]);
    if (typeof flags === "string") return usage(flags);
    const check = flags.check === true;
    const repoRoot = flags.root ? String(flags.root) : flags.plan ? undefined : findRepoRoot(process.cwd());
    if (repoRoot) return runGenerate({ check, root: repoRoot });
    return runProjectGenerate({
      check,
      plan: flags.plan as string | undefined,
      javaPackage: flags["java-package"] as string | undefined,
    });
  }
  if (command === "init") {
    const flags = parse(rest, ["framework", "endpoint"], ["force"]);
    if (typeof flags === "string") return usage(flags);
    return runInit({
      force: flags.force === true,
      framework: flags.framework as Framework | undefined,
      endpoint: flags.endpoint as string | undefined,
    });
  }
  if (command === "validate") {
    const flags = parse(rest, ["plan", "against"], []);
    if (typeof flags === "string") return usage(flags);
    return runValidate({ plan: flags.plan as string | undefined, against: flags.against as string | undefined });
  }
  if (command === "dev") {
    const flags = parse(rest, ["port", "plan", "host"], []);
    if (typeof flags === "string") return usage(flags);
    const port = flags.port === undefined ? 8124 : Number(flags.port);
    if (!Number.isInteger(port) || port < 0 || port > 65535) return usage("--port must be a port number");
    try {
      const dev = await startDevServer({ port, plan: flags.plan as string | undefined, host: flags.host as string | undefined });
      const stop = () => void dev.close().then(() => process.exit(0));
      process.on("SIGINT", stop);
      process.on("SIGTERM", stop);
      return -1; // keep running
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      console.error(code === "EADDRINUSE" ? `omnirec dev: port ${port} is in use; try --port <n>` : `omnirec dev: ${(error as Error).message}`);
      return 1;
    }
  }
  console.error(`omnirec: unknown command "${command}"\n\n${HELP}`);
  return 2;
}

main(process.argv.slice(2)).then((code) => {
  if (code >= 0) process.exitCode = code;
});
