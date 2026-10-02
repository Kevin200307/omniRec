// SPDX-License-Identifier: Apache-2.0
import { existsSync, watch } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { resolve } from "node:path";
import { indexWithPlan, loadPlanFile, PlanError } from "../project/plan";
import type { EventIndex } from "../project/runtime";
import { checkEvent, type EventProblem } from "../project/validate-event";

export interface DevOptions {
  port?: number;
  host?: string;
  /** Tracking plan. Default: omnirec.plan.yaml in cwd, when it exists. */
  plan?: string;
  cwd?: string;
  /** Colour output. Default: on for a terminal, off with NO_COLOR. */
  color?: boolean;
  log?: (line: string) => void;
  /** Reload the plan when the file changes. Default true. */
  watch?: boolean;
}

export interface ReceivedEvent {
  receivedAt: string;
  event: string;
  canonical?: string;
  status: "accepted" | "rejected" | "duplicate";
  unplanned: boolean;
  errors: EventProblem[];
  payload: unknown;
}

export interface DevServer {
  server: Server;
  url: string;
  received: ReceivedEvent[];
  close(): Promise<void>;
}

const MAX_BODY = 1024 * 1024;
const KEEP = 500;

/**
 * `omnirec dev`: a local collector for development. It accepts what the
 * Event API accepts (`/v1/events`, `/v1/events/batch`, `/v1/identify`,
 * `GET /v1/catalog`), validates against the catalog and your plan, prints each
 * event, and shows a live list at `/`. No Java, Docker or key needed; any
 * origin may send. Events go nowhere else.
 */
export async function startDevServer(options: DevOptions = {}): Promise<DevServer> {
  const log = options.log ?? ((line) => console.log(line));
  const cwd = resolve(options.cwd ?? process.cwd());
  const color = options.color ?? (process.stdout.isTTY === true && !process.env.NO_COLOR);
  const paint = (code: string, text: string) => (color ? `\u001b[${code}m${text}\u001b[0m` : text);

  const planPath = resolve(cwd, options.plan ?? "omnirec.plan.yaml");
  const hasPlan = existsSync(planPath);
  if (options.plan && !hasPlan) throw new Error(`plan ${planPath} not found`);
  let index = loadIndex(hasPlan ? planPath : undefined, log, paint);

  const received: ReceivedEvent[] = [];
  const seen = new Set<string>();

  const server = createServer(async (req, res) => {
    cors(res);
    if (req.method === "OPTIONS") return end(res, 204);
    const path = (req.url ?? "/").split("?")[0];
    try {
      if (req.method === "GET" && path === "/") return html(res, PAGE);
      if (req.method === "GET" && path === "/__events") return json(res, 200, received);
      if (req.method === "GET" && path === "/v1/catalog") return json(res, 200, catalogBody(index));
      if (req.method === "POST" && (path === "/v1/events" || path === "/v1/events/batch")) {
        const body = await readJson(req);
        const events = Array.isArray(body?.events) ? body.events : Array.isArray(body) ? body : body ? [body] : [];
        return json(res, 202, ingest(events));
      }
      if (req.method === "POST" && path === "/v1/identify") {
        const body = await readJson(req);
        if (!body?.anonymousId || !body?.userId) return json(res, 400, { status: 400, error: "anonymousId and userId are both required" });
        log(`${paint("36", "identify")} ${body.anonymousId} -> ${paint("1", String(body.userId))}`);
        return json(res, 202, { accepted: 1, rejected: 0, duplicates: 0, retryLater: 0, errors: [] });
      }
      return json(res, 404, { status: 404, error: "not found" });
    } catch (error) {
      const tooLarge = (error as Error).message === "too large";
      return json(res, tooLarge ? 413 : 400, { status: tooLarge ? 413 : 400, error: tooLarge ? "payload too large" : "request body is not valid JSON" });
    }
  });

  function ingest(events: unknown[]) {
    let accepted = 0;
    let duplicates = 0;
    const errors: Array<{ eventId: string | null; reason: string }> = [];
    for (const raw of events) {
      const event = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
      const name = String(event.event ?? event.eventType ?? "?");
      const id = typeof event.eventId === "string" ? event.eventId : null;
      const check = checkEvent(event, index);
      const entry: ReceivedEvent = {
        receivedAt: new Date().toISOString(),
        event: name,
        canonical: check.event && check.event.name !== name ? check.event.name : undefined,
        status: "accepted",
        unplanned: check.unplanned,
        errors: check.errors,
        payload: event,
      };
      if (!check.valid) {
        entry.status = "rejected";
        errors.push({ eventId: id, reason: check.errors.map((e) => `${e.field}: ${e.message}`).join("; ") });
        log(`${paint("31", "✗ rejected")} ${paint("1", name)}`);
        for (const problem of check.errors) log(`    ${paint("31", problem.field)}: ${problem.message}`);
      } else if (id && seen.has(id)) {
        entry.status = "duplicate";
        duplicates++;
        log(`${paint("90", "= duplicate")} ${name} ${paint("90", id)}`);
      } else {
        if (id) seen.add(id);
        accepted++;
        const alias = entry.canonical ? paint("90", ` (alias of ${entry.canonical})`) : "";
        const note = check.unplanned ? paint("33", "  unplanned: not in the catalog or your plan, stored but never sent to providers") : "";
        log(`${paint("32", "✓")} ${paint("1", name)}${alias} ${summary(event)}${note}`);
      }
      received.unshift(entry);
      if (received.length > KEEP) received.pop();
    }
    return { accepted, rejected: errors.length, duplicates, retryLater: 0, errors };
  }

  const watcher = hasPlan && options.watch !== false
    ? watch(planPath, () => {
        index = loadIndex(planPath, log, paint, index);
      })
    : undefined;

  const port = options.port ?? 8124;
  const host = options.host ?? "localhost";
  await new Promise<void>((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => resolveListen());
  });
  const address = server.address();
  const actualPort = typeof address === "object" && address ? address.port : port;
  const url = `http://${host}:${actualPort}`;
  log(`omnirec dev: collecting on ${paint("1", url)}  (live list at ${url}/)`);
  log(`  catalog: ${index.catalog.events.length} standard events${hasPlan ? `; plan: ${index.custom.length} custom event(s) from ${planPath}` : "; no plan"}`);
  log(`  point the SDK at it: createOmnirec({ endpoint: "${url}" })`);

  return {
    server,
    url,
    received,
    close: () =>
      new Promise<void>((done) => {
        watcher?.close();
        server.closeAllConnections?.();
        server.close(() => done());
      }),
  };
}

function loadIndex(
  planPath: string | undefined,
  log: (line: string) => void,
  paint: (code: string, text: string) => string,
  previous?: EventIndex
): EventIndex {
  if (!planPath) return indexWithPlan(undefined);
  try {
    const index = indexWithPlan(loadPlanFile(planPath));
    if (previous) log(paint("36", `plan reloaded: ${index.custom.length} custom event(s)`));
    return index;
  } catch (error) {
    if (!(error instanceof PlanError)) throw error;
    log(paint("31", "plan has problems; " + (previous ? "keeping the previous version" : "running with the standard catalog only") + ":"));
    for (const problem of error.problems) log(`  ${problem}`);
    return previous ?? indexWithPlan(undefined);
  }
}

function summary(event: Record<string, unknown>): string {
  const data = (event.data ?? {}) as Record<string, Record<string, unknown> | undefined>;
  const identity = (event.identity ?? {}) as Record<string, unknown>;
  const parts: string[] = [];
  for (const block of ["product", "cart", "order", "search"]) {
    const value = data[block];
    if (value && typeof value === "object") {
      const id = value.id ?? value.query;
      if (id !== undefined) parts.push(`${block}=${String(id)}`);
    }
  }
  parts.push(identity.userId ? `user=${String(identity.userId)}` : `anon=${String(identity.anonymousId ?? "?").slice(0, 12)}`);
  return parts.join(" ");
}

function catalogBody(index: EventIndex) {
  return {
    tenantId: "dev",
    catalogVersion: index.catalog.catalogVersion,
    validationMode: "permissive",
    vocabularies: index.vocabularies,
    events: index.events.map((e) => ({
      name: e.name, domain: e.domain, version: e.version, kind: e.kind, sources: e.sources,
      aliases: e.aliases, required: e.required, fields: e.fields,
    })),
  };
}

function readJson(req: IncomingMessage): Promise<any> {
  return new Promise((resolveBody, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new Error("too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      const text = Buffer.concat(chunks).toString("utf8");
      try {
        resolveBody(text ? JSON.parse(text) : null);
      } catch {
        reject(new Error("invalid json"));
      }
    });
    req.on("error", reject);
  });
}

function cors(res: ServerResponse) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Omnirec-Key, X-Omnirec-Tenant");
}

function end(res: ServerResponse, status: number) {
  res.writeHead(status);
  res.end();
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function html(res: ServerResponse, body: string) {
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  res.end(body);
}

const PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>omnirec dev</title>
<style>
:root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#e5e5ea;--ok:#1a7f37;--bad:#cf222e;--warn:#9a6700}
@media (prefers-color-scheme:dark){:root{--bg:#161618;--fg:#f2f2f7;--muted:#98989d;--line:#2c2c2e;--ok:#3fb950;--bad:#f85149;--warn:#d29922}}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
header{padding:16px;border-bottom:1px solid var(--line)}h1{font-size:16px;margin:0}
p{margin:4px 0 0;color:var(--muted)}main{padding:0 16px}
.row{border-bottom:1px solid var(--line);padding:10px 0}.name{font-weight:600}
.accepted .mark{color:var(--ok)}.rejected .mark{color:var(--bad)}.duplicate .mark{color:var(--muted)}
.meta{color:var(--muted);font-size:12px}.err{color:var(--bad);font-size:13px}.warn{color:var(--warn);font-size:12px}
pre{margin:6px 0 0;overflow:auto;font-size:12px;background:color-mix(in srgb,var(--line) 40%,transparent);padding:8px;border-radius:6px}
details summary{cursor:pointer;color:var(--muted);font-size:12px}
</style></head><body>
<header><h1>omnirec dev</h1><p>Events received by the local collector, newest first. Nothing is sent anywhere else.</p></header>
<main id="list"><p>Waiting for events…</p></main>
<script>
const list=document.getElementById("list");
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
async function refresh(){
  try{
    const events=await (await fetch("/__events")).json();
    if(!events.length)return;
    list.innerHTML=events.map(e=>'<div class="row '+e.status+'"><span class="mark">'+(e.status==="accepted"?"✓":e.status==="rejected"?"✗":"=")+'</span> <span class="name">'+esc(e.event)+'</span>'
      +(e.canonical?' <span class="meta">alias of '+esc(e.canonical)+'</span>':'')
      +' <span class="meta">'+esc(e.receivedAt.slice(11,19))+'</span>'
      +(e.unplanned?'<div class="warn">unplanned: not in the catalog or your plan</div>':'')
      +e.errors.map(x=>'<div class="err">'+esc(x.field)+': '+esc(x.message)+'</div>').join("")
      +'<details><summary>payload</summary><pre>'+esc(JSON.stringify(e.payload,null,2))+'</pre></details></div>').join("");
  }catch{}
}
refresh();setInterval(refresh,1000);
</script></body></html>`;
