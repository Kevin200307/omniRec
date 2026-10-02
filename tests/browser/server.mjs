// SPDX-License-Identifier: Apache-2.0
// Minimal static server for browser tests. No dependencies on purpose.
//
//   /            -> tests/browser/fixtures/
//   /sdk/web/    -> packages/commerce-web/dist/
//   /health      -> 200, used by Playwright to know the server is up
//
// Specs intercept /collector/** with page.route, so each test sees exactly
// the requests its page made. /sink/** is a recording collector for requests
// interception cannot see (beacons during unload); read it from /__received.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const repoRoot = resolve(here, "../..");
const port = Number(process.argv[2] ?? 4317);
const received = [];

const mounts = [
  ["/sdk/web/", join(repoRoot, "packages/commerce-web/dist")],
  ["/", join(here, "fixtures")],
];

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".cjs": "text/javascript; charset=utf-8",
  ".map": "application/json",
  ".json": "application/json",
};

createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url ?? "/", "http://x").pathname);
  if (path === "/health") {
    res.writeHead(200).end("ok");
    return;
  }
  // A real collector stand-in for requests page.route cannot see, such as a
  // beacon sent while the page unloads. Tests read what arrived from /__received.
  if (path.startsWith("/sink/") && req.method === "POST") {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      received.push(body);
      res.writeHead(202, { "content-type": "application/json" }).end("{}");
    });
    return;
  }
  if (path === "/__received") {
    res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(received));
    return;
  }
  for (const [prefix, dir] of mounts) {
    if (!path.startsWith(prefix)) continue;
    const file = normalize(join(dir, path.slice(prefix.length) || "index.html"));
    if (!file.startsWith(dir)) break; // path traversal
    try {
      const body = await readFile(file);
      res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
      res.end(body);
      return;
    } catch {
      break;
    }
  }
  res.writeHead(404).end("not found");
}).listen(port, () => console.log(`browser test server on http://localhost:${port}`));
