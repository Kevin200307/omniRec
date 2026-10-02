// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Request } from "@playwright/test";

test("built commerce-web sends a tracked event from a real browser", async ({ page, context }) => {
  const batches: Request[] = [];
  await page.route("**/collector/**", async (route) => {
    batches.push(route.request());
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({ accepted: 1, rejected: 0 }),
    });
  });

  await page.goto("/smoke.html");
  await expect(page.locator("body")).toHaveAttribute("data-ready", "true");

  await page.click("#buy");
  await expect.poll(() => batches.length).toBe(1);

  const request = batches[0];
  expect(request.method()).toBe("POST");
  expect(new URL(request.url()).pathname).toBe("/collector/v1/events/batch");

  const body = request.postDataJSON();
  expect(body.events).toHaveLength(1);
  const event = body.events[0];
  expect(event.event).toBe("product_added_to_cart");
  expect(event.schemaVersion).toBe("2.0");
  expect(event.data).toMatchObject({ product: { id: "P100", quantity: 1 }, cart: { id: "c1" } });
  expect(event.identity.anonymousId).toBeTruthy();
  expect(event.identity.sessionId).toBeTruthy();

  // The anonymous id lives in a first-party cookie, so it survives reloads.
  const cookies = await context.cookies();
  const anon = cookies.find((c) => c.name === "omnirec_anonymous_id");
  expect(anon?.value).toBe(event.identity.anonymousId);

  expect(await page.evaluate(() => (window as any).__omnirec.errors)).toEqual([]);
});

test("keyless v2 client posts to a same-site collector path with no key", async ({ page, context }) => {
  const batches: Request[] = [];
  await page.route("**/collector/**", async (route) => {
    batches.push(route.request());
    await route.fulfill({ status: 202, contentType: "application/json", body: "{}" });
  });

  await page.goto("/keyless.html");
  await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
  await page.evaluate(() => (window as any).__omnirec.omnirec.flush());
  await expect.poll(() => batches.length).toBeGreaterThan(0);

  const request = batches[0];
  expect(new URL(request.url()).pathname).toBe("/collector/v1/events/batch");
  expect(request.headers()["x-omnirec-key"]).toBeUndefined();
  const events = batches.flatMap((b) => b.postDataJSON().events);
  expect(events.map((e: { event: string }) => e.event)).toEqual(["session_started", "product_viewed"]);
  expect(events[1].data.product).toEqual({ id: "P100", price: "12.50", currency: "USD" });

  const cookies = await context.cookies();
  expect(cookies.find((c) => c.name === "omnirec_session_id")?.value).toBe(events[1].identity.sessionId);
  expect(await page.evaluate(() => (window as any).__omnirec.errors)).toEqual([]);
});
