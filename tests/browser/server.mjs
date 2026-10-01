// SPDX-License-Identifier: Apache-2.0
// Minimal static server for browser tests. No dependencies on purpose.
//
//   /            -> tests/browser/fixtures/
//   /sdk/web/    -> packages/commerce-web/dist/
//   /health      -> 200, used by Playwright to know the server is up
//
// The collector is not served here: specs intercept /collector/** with
// page.route, so each test sees exactly the requests its page made.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const repoRoot = resolve(here, "../..");
const port = Number(process.argv[2] ?? 4317);

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
