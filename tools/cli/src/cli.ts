#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { runGenerate } from "./commands/generate";

const HELP = `omnirec <command> [options]

Commands:
  generate            Generate SDK types, schema and docs from catalog/
    --check           Report out-of-date files and exit 1 instead of writing
    --root <dir>      Repository root (default: nearest folder with catalog/catalog.yaml)

  help                Show this message
`;

function main(argv: string[]): number {
  const [command, ...rest] = argv;
  if (!command || command === "help" || command === "--help" || command === "-h") {
    console.log(HELP);
    return command ? 0 : 2;
  }
  if (command === "generate") {
    let check = false;
    let root: string | undefined;
    for (let i = 0; i < rest.length; i++) {
      const arg = rest[i];
      if (arg === "--check") check = true;
      else if (arg === "--root") root = rest[++i];
      else {
        console.error(`omnirec generate: unknown option ${arg}\n\n${HELP}`);
        return 2;
      }
    }
    return runGenerate({ check, root });
  }
  console.error(`omnirec: unknown command "${command}"\n\n${HELP}`);
  return 2;
}

process.exitCode = main(process.argv.slice(2));
