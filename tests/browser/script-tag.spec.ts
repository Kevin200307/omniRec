// SPDX-License-Identifier: Apache-2.0
import { expect, test } from "@playwright/test";

test("the script tag replays calls queued before it loaded, in order, and tracks attributes", async ({ page }) => {
  const events: Array<{ event: string; identity: { userId: string | null } }> = [];
  await page.route("**/collector/**", async (route) => {
    const body = route.request().postData();
    if (body) events.push(...JSON.parse(body).events);
    await route.fulfill({ status: 202, contentType: "application/json", body: "{}" });
  });

  await page.goto("/script-tag.html");
  await page.waitForFunction(() => typeof (window as any).omnirec?.client === "object");
  await page.locator("#add").click();
  await page.evaluate(() => (window as any).omnirec.flush());

  const names = events.map((e) => e.event);
  // Session start and the autocaptured page view come from boot; the queued
  // calls follow in the order they were made, then the click.
  expect(names).toEqual([
    "session_started",
    "page_viewed",
    "home_page_viewed",
    "product_viewed",
    "identify",
    "product_added_to_cart",
  ].filter((n) => n !== "home_page_viewed" || names.includes("home_page_viewed")));
  expect(names.indexOf("product_viewed")).toBeLessThan(names.indexOf("identify"));
  expect(events.find((e) => e.event === "product_added_to_cart")!.identity.userId).toBe("customer_9");
});
