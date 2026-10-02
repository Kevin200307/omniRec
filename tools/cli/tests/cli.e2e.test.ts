// SPDX-License-Identifier: Apache-2.0
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

/**
 * The quick start, with the built CLI and the built browser SDK: init, then
 * generate, then dev, then events from the SDK reach the local collector and
 * are validated against catalog + plan.
 */
const CLI = resolve(__dirname, "../dist/cli.js");
const SDK = resolve(__dirname, "../../../packages/commerce-web/dist/index.js");
const built = existsSync(CLI) && existsSync(SDK);

describe.skipIf(!built)("quick start, end to end", () => {
  let dev: ChildProcess | undefined;

  afterAll(() => {
    dev?.kill();
  });

  it("init -> generate -> dev -> the SDK's events are accepted and checked", async () => {
    const started = Date.now();
    const cwd = mkdtempSync(join(tmpdir(), "omnirec-quickstart-"));
    writeFileSync(join(cwd, "package.json"), JSON.stringify({ name: "shop", dependencies: { react: "19" } }));
    const cli = (...args: string[]) => spawnSync(process.execPath, [CLI, ...args], { cwd, encoding: "utf8" });

    const init = cli("init");
    expect(init.status).toBe(0);
    expect(init.stdout).toContain("Detected: React");

    const generate = cli("generate");
    expect(generate.status, generate.stdout + generate.stderr).toBe(0);
    expect(readFileSync(join(cwd, "omnirec.d.ts"), "utf8")).toContain("size_guide_opened");
    expect(cli("validate").status).toBe(0);

    dev = spawn(process.execPath, [CLI, "dev", "--port", "0"], { cwd, env: { ...process.env, NO_COLOR: "1" } });
    let output = "";
    const url = await new Promise<string>((resolveUrl, reject) => {
      const timer = setTimeout(() => reject(new Error("dev did not start: " + output)), 15_000);
      dev!.stdout!.on("data", (chunk: Buffer) => {
        output += chunk.toString();
        const match = output.match(/collecting on (http:\/\/\S+)/);
        if (match) {
          clearTimeout(timer);
          resolveUrl(match[1]);
        }
      });
    });

    const { createOmnirec } = await import(pathToFileURL(SDK).href);
    const omnirec = createOmnirec({ endpoint: url, maxBatchSize: 50 });
    omnirec.track("size_guide_opened", { product: { id: "P1" }, unit: "cm" });
    omnirec.track("add_to_cart", { product: { id: "P1", quantity: 1 } });
    omnirec.trackUntyped("size_guide_opened", { unit: "cm" }); // missing product.id: the collector says so
    await omnirec.flush();
    omnirec.destroy();

    const received: Array<{ event: string; status: string; errors: Array<{ field: string }> }> =
      (await (await fetch(url + "/__events")).json()) as any;
    const byStatus = (status: string) => received.filter((r) => r.status === status).map((r) => r.event);
    expect(byStatus("accepted")).toEqual(expect.arrayContaining(["size_guide_opened", "add_to_cart"]));
    const rejected = received.find((r) => r.status === "rejected");
    expect(rejected?.event).toBe("size_guide_opened");
    expect(rejected?.errors.map((e) => e.field)).toContain("data.product.id");
    expect(output).toContain("✓ add_to_cart (alias of product_added_to_cart)");

    // The README promises a validated first event in under five minutes; the tooling part takes seconds.
    expect(Date.now() - started).toBeLessThan(60_000);
  }, 60_000);
});
