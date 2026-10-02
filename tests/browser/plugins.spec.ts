// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Page } from "@playwright/test";

interface SentEvent {
  event: string;
  data: Record<string, unknown>;
  context: { campaign?: Record<string, string> };
}

async function collect(page: Page): Promise<SentEvent[]> {
  const events: SentEvent[] = [];
  await page.route("**/collector/**", async (route) => {
    const body = route.request().postData();
    if (body) events.push(...JSON.parse(body).events);
    await route.fulfill({ status: 202, contentType: "application/json", body: "{}" });
  });
  return events;
}

const flush = (page: Page) => page.evaluate(() => (window as any).__omnirec.omnirec.flush());

test("a store declared with attributes only produces the expected events", async ({ page }) => {
  const events = await collect(page);
  await page.goto("/store.html?utm_source=newsletter&gclid=abc");
  await expect(page.locator("body")).toHaveAttribute("data-ready", "true");

  // The promotion is above the fold: a real IntersectionObserver reports it.
  await page.waitForTimeout(500);
  await page.locator("#add span").click();
  await page.locator("#open").click(); // hash navigation, not a history.pushState
  await flush(page);

  const names = events.map((e) => e.event);
  expect(names).toEqual(["page_viewed", "product_list_viewed", "product_added_to_cart", "product_clicked"]);

  const added = events[2];
  expect(added.data).toEqual({
    product: { id: "P100", price: "12.50", currency: "USD", quantity: 1 },
    list: { id: "home_featured", position: 1 },
  });
  expect(events[1].data).toEqual({ list: { id: "autumn_sale", productIds: ["P1", "P2"] } });
  expect(events.every((e) => e.context.campaign?.clickId === "abc")).toBe(true);
  expect(await page.evaluate(() => (window as any).__omnirec.errors)).toEqual([]);
});

test("an element must actually be seen to count as an impression", async ({ page }) => {
  const events = await collect(page);
  await page.setViewportSize({ width: 800, height: 600 });
  // A long visibility threshold, so scrolling away beats it even on a busy machine.
  await page.goto("/store.html?minVisibleMs=2000");
  await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
  await page.evaluate(() => window.scrollTo(0, 2000));
  await page.waitForTimeout(2500);
  await flush(page);
  expect(events.map((e) => e.event)).not.toContain("product_list_viewed");
});

test("events still queued when the page closes are sent with sendBeacon", async ({ page, request }) => {
  await page.goto("/store.html?endpoint=/sink");
  await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
  const anonymousId = await page.evaluate(() => (window as any).__omnirec.omnirec.getIdentity().anonymousId);
  await page.locator("#add").click();
  // maxWaitMs is 60s, so nothing has been sent yet; leaving the page must flush.
  await page.goto("about:blank");
  await expect
    .poll(async () => {
      const received: string[] = await (await request.get("/__received")).json();
      return received.some((body) => body.includes(anonymousId) && body.includes("product_added_to_cart"));
    })
    .toBe(true);
});
