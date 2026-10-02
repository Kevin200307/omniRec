// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createOmnirec, type OmnirecClient } from "../src/core/omnirec";
import type { OmnirecPlugin } from "../src/core/pipeline";
import type { CommerceEvent } from "../src/events/types";
import { autocapture, parseCampaign } from "../src/plugins/autocapture";
import { consent } from "../src/plugins/consent";
import { debug, distance, suggest } from "../src/plugins/debug";
import { dom } from "../src/plugins/dom";
import { collectFields } from "../src/plugins/fields";
import { impressions, PAGEVIEW_EVENT } from "../src/plugins/impressions";

let clients: OmnirecClient[] = [];

function harness(plugins: Array<OmnirecPlugin | OmnirecPlugin[]>) {
  const sent: CommerceEvent[] = [];
  const errors: Error[] = [];
  const client = createOmnirec({
    endpoint: "https://events.example.com",
    autoTrackSessions: false,
    maxBatchSize: 1000,
    fetchImpl: (async (_url: string, init?: RequestInit) => {
      sent.push(...JSON.parse(String(init?.body)).events);
      return new Response(null, { status: 202 });
    }) as unknown as typeof fetch,
    onError: (e) => errors.push(e),
    plugins,
  });
  clients.push(client);
  return {
    client,
    errors,
    async events(): Promise<CommerceEvent[]> {
      await client.flush();
      return sent;
    },
  };
}

beforeEach(() => {
  document.body.innerHTML = "";
  localStorage.clear();
  sessionStorage.clear();
  history.replaceState(null, "", "/");
});

afterEach(() => {
  for (const client of clients) client.destroy();
  clients = [];
  vi.useRealTimers();
});

describe("collectFields", () => {
  it("maps known attributes to blocks, coerces by type, and inherits from ancestors (nearest wins)", () => {
    document.body.innerHTML = `
      <section data-omnirec-list="home_featured" data-omnirec-currency="EUR">
        <article data-omnirec-product="P100" data-omnirec-price="12.50" data-omnirec-currency="USD" data-omnirec-position="3">
          <button id="b" data-omnirec-event="product_added_to_cart" data-omnirec-quantity="2"
                  data-omnirec-gift-wrap="true" data-omnirec-props='{"source":"grid"}'>Add</button>
        </article>
      </section>`;
    const fields = collectFields(document.getElementById("b")!);
    expect(fields.data).toEqual({
      product: { id: "P100", price: "12.50", currency: "USD", quantity: 2 },
      list: { id: "home_featured", position: 3 },
      giftWrap: true,
    });
    expect(fields.properties).toEqual({ source: "grid" });
    expect(fields.problems).toEqual([]);
  });

  it("reports invalid JSON instead of throwing", () => {
    document.body.innerHTML = `<button id="b" data-omnirec-props="{oops">x</button>`;
    expect(collectFields(document.getElementById("b")!).problems).toEqual(["data-omnirec-props is not valid JSON"]);
  });

  it("takes custom data as JSON for structures attributes cannot express", () => {
    document.body.innerHTML = `<button id="b" data-omnirec-data='{"order":{"items":[{"productId":"P1"}]}}'>x</button>`;
    expect(collectFields(document.getElementById("b")!).data).toEqual({ order: { items: [{ productId: "P1" }] } });
  });
});

describe("dom plugin", () => {
  it("tracks a click on a nested child of the declaring element", async () => {
    document.body.innerHTML = `
      <div data-omnirec-product="P100">
        <button data-omnirec-event="product_added_to_cart" data-omnirec-quantity="1"><span id="label">Add</span></button>
      </div>`;
    const h = harness([dom()]);
    document.getElementById("label")!.click();
    const [event] = await h.events();
    expect(event.event).toBe("product_added_to_cart");
    expect(event.data).toEqual({ product: { id: "P100", quantity: 1 } });
  });

  it("works for elements added after setup, without rebinding", async () => {
    const h = harness([dom()]);
    document.body.innerHTML = `<button id="late" data-omnirec-event="page_viewed">x</button>`;
    document.getElementById("late")!.click();
    expect((await h.events()).map((e) => e.event)).toEqual(["page_viewed"]);
  });

  it("uses submit for forms and change for controls", async () => {
    document.body.innerHTML = `
      <form id="f" data-omnirec-event="search_performed" data-omnirec-search="shoes"><button>Go</button></form>
      <select id="s" data-omnirec-event="product_list_viewed" data-omnirec-data='{"list":{"productIds":["a"]}}'></select>`;
    const h = harness([dom()]);
    document.getElementById("f")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    document.getElementById("s")!.dispatchEvent(new Event("change", { bubbles: true }));
    document.getElementById("s")!.click(); // a click on a select is not its trigger
    expect((await h.events()).map((e) => e.event)).toEqual(["search_performed", "product_list_viewed"]);
  });

  it("honours data-omnirec-on", async () => {
    document.body.innerHTML = `<input id="i" type="text" data-omnirec-event="page_viewed" data-omnirec-on="click">`;
    const h = harness([dom()]);
    document.getElementById("i")!.dispatchEvent(new Event("change", { bubbles: true }));
    document.getElementById("i")!.click();
    expect(await h.events()).toHaveLength(1);
  });

  it("stops listening when the client is destroyed", async () => {
    document.body.innerHTML = `<button id="b" data-omnirec-event="page_viewed">x</button>`;
    const h = harness([dom()]);
    h.client.destroy();
    document.getElementById("b")!.click();
    expect(h.errors).toEqual([]);
  });
});

describe("impressions plugin", () => {
  class FakeObserver {
    static instances: FakeObserver[] = [];
    observed = new Set<Element>();
    constructor(private readonly callback: IntersectionObserverCallback) {
      FakeObserver.instances.push(this);
    }
    observe(element: Element) {
      this.observed.add(element);
    }
    unobserve(element: Element) {
      this.observed.delete(element);
    }
    disconnect() {
      this.observed.clear();
    }
    show(element: Element, ratio = 1) {
      this.callback(
        [{ target: element, isIntersecting: ratio > 0, intersectionRatio: ratio } as unknown as IntersectionObserverEntry],
        this as unknown as IntersectionObserver
      );
    }
  }

  beforeEach(() => {
    FakeObserver.instances = [];
    vi.stubGlobal("IntersectionObserver", FakeObserver);
    vi.useFakeTimers();
  });
  afterEach(() => vi.unstubAllGlobals());

  const card = () => {
    document.body.innerHTML = `<div id="c" data-omnirec-impression="product_list_viewed" data-omnirec-list="home"
      data-omnirec-data='{"list":{"productIds":["P1","P2"]}}'></div>`;
    return document.getElementById("c")!;
  };

  it("fires after the element stays visible for the minimum time, once per page view", async () => {
    const element = card();
    const h = harness([impressions()]);
    const observer = FakeObserver.instances[0];
    observer.show(element);
    vi.advanceTimersByTime(999);
    observer.show(element, 0);
    observer.show(element);
    vi.advanceTimersByTime(1000);
    observer.show(element, 0);
    observer.show(element);
    vi.advanceTimersByTime(1000);
    vi.useRealTimers();
    const events = await h.events();
    expect(events).toHaveLength(1);
    expect(events[0].data).toEqual({ list: { id: "home", productIds: ["P1", "P2"] } });
  });

  it("ignores elements less visible than the threshold", async () => {
    const element = card();
    const h = harness([impressions()]);
    FakeObserver.instances[0].show(element, 0.3);
    vi.advanceTimersByTime(2000);
    vi.useRealTimers();
    expect(await h.events()).toEqual([]);
  });

  it("counts the element again after a page view", async () => {
    const element = card();
    const h = harness([impressions()]);
    const observer = FakeObserver.instances[0];
    observer.show(element);
    vi.advanceTimersByTime(1000);
    window.dispatchEvent(new CustomEvent(PAGEVIEW_EVENT));
    observer.show(element, 0);
    observer.show(element);
    vi.advanceTimersByTime(1000);
    vi.useRealTimers();
    expect(await h.events()).toHaveLength(2);
  });

  it("samples per element", () => {
    card();
    harness([impressions({ sampleRate: 0.5, random: () => 0.9 })]);
    expect(FakeObserver.instances[0].observed.size).toBe(0);
  });
});

describe("autocapture plugin", () => {
  it("sends page views, home page views and SPA navigations, once per URL", async () => {
    const h = harness([autocapture({ scrollDepth: false })]);
    history.pushState(null, "", "/shoes");
    history.pushState(null, "", "/shoes");
    history.replaceState(null, "", "/shoes?page=2");
    const names = (await h.events()).map((e) => e.event);
    expect(names).toEqual(["page_viewed", "home_page_viewed", "page_viewed", "page_viewed"]);
  });

  it("parses UTM parameters and click ids, and keeps them for the session", async () => {
    expect(parseCampaign("?utm_source=google&utm_medium=cpc&gclid=abc&x=1")).toEqual({
      source: "google",
      medium: "cpc",
      clickId: "abc",
      clickIdType: "gclid",
    });
    expect(parseCampaign("?q=shoes")).toBeUndefined();

    history.replaceState(null, "", "/?utm_source=newsletter&utm_campaign=autumn");
    const first = harness([autocapture({ scrollDepth: false })]);
    first.client.destroy();
    history.replaceState(null, "", "/next");
    const h = harness([autocapture({ scrollDepth: false })]);
    const [event] = await h.events();
    expect(event.context.campaign).toEqual({ source: "newsletter", name: "autumn" });
  });

  it("sends product_viewed for a declared product page, including content rendered later", async () => {
    const h = harness([autocapture({ scrollDepth: false, dwellTime: false })]);
    document.body.innerHTML = `<main data-omnirec-page="product" data-omnirec-product="P9" data-omnirec-price="5.00"></main>`;
    await new Promise((resolve) => setTimeout(resolve, 0));
    const product = (await h.events()).find((e) => e.event === "product_viewed");
    expect(product?.data).toEqual({ product: { id: "P9", price: "5.00" } });
    expect(product?.context.page).toEqual({ type: "product" });
  });

  it("reports scroll depth thresholds once each", async () => {
    Object.defineProperty(document.documentElement, "scrollHeight", { value: 2000, configurable: true });
    Object.defineProperty(window, "innerHeight", { value: 500, configurable: true });
    const h = harness([autocapture({ pageViews: false, homePath: null })]);
    Object.defineProperty(window, "scrollY", { value: 600, configurable: true });
    window.dispatchEvent(new Event("scroll"));
    window.dispatchEvent(new Event("scroll"));
    const events = (await h.events()).filter((e) => e.event === "scroll_depth_reached");
    expect(events.map((e) => e.data.percent)).toEqual([25, 50]);
  });

  it("restores the History API on destroy", () => {
    const push = history.pushState;
    const h = harness([autocapture()]);
    expect(history.pushState).not.toBe(push);
    h.client.destroy();
    expect(history.pushState).toBe(push);
  });
});

describe("consent plugin", () => {
  it("holds events until consent, then sends them", async () => {
    const cmp = consent({ persist: false });
    const h = harness([cmp]);
    h.client.track("page_viewed");
    expect(await h.events()).toEqual([]);
    cmp.set({ analytics: true });
    expect((await h.events()).map((e) => e.event)).toEqual(["page_viewed"]);
  });

  it("drops held and later events when denied", async () => {
    const cmp = consent({ persist: false });
    const h = harness([cmp]);
    h.client.track("page_viewed");
    cmp.set({ analytics: false });
    h.client.track("home_page_viewed");
    expect(await h.events()).toEqual([]);
    expect(cmp.state().analytics).toBe("denied");
  });

  it("caps the queue, dropping the oldest", async () => {
    const cmp = consent({ persist: false, maxQueued: 2 });
    const h = harness([cmp]);
    h.client.track("page_viewed");
    h.client.track("home_page_viewed");
    h.client.track("session_ended");
    cmp.set({ analytics: true });
    expect((await h.events()).map((e) => e.event)).toEqual(["home_page_viewed", "session_ended"]);
  });

  it("remembers the choice", async () => {
    consent().set({ analytics: true });
    const h = harness([consent()]);
    h.client.track("page_viewed");
    expect(await h.events()).toHaveLength(1);
  });

  it("can route events to categories", async () => {
    const cmp = consent({ persist: false, categoryOf: (e) => (e.event === "page_viewed" ? "marketing" : "analytics") });
    const h = harness([cmp]);
    h.client.track("page_viewed");
    h.client.track("home_page_viewed");
    cmp.set({ analytics: true });
    expect((await h.events()).map((e) => e.event)).toEqual(["home_page_viewed"]);
  });
});

describe("debug plugin", () => {
  const logger = () => ({ log: vi.fn(), warn: vi.fn(), groupCollapsed: vi.fn(), groupEnd: vi.fn() });

  it("suggests the intended name for a typo", () => {
    expect(distance("add_to_card", "add_to_cart")).toBe(1);
    expect(suggest("product_veiwed", ["product_viewed", "page_viewed"])).toBe("product_viewed");
    expect(suggest("zzz_unrelated", ["product_viewed"])).toBeUndefined();

    const out = logger();
    const h = harness([debug({ logger: out, knownNames: ["add_to_cart"] })]);
    h.client.trackUntyped("add_to_card", {});
    expect(out.warn).toHaveBeenCalledWith(expect.stringContaining('Did you mean "add_to_cart"?'));
  });

  it("warns about likely double tracking", () => {
    let time = 0;
    const out = logger();
    const h = harness([debug({ logger: out, now: () => time })]);
    h.client.track("product_viewed", { product: { id: "P1" } });
    time = 100;
    h.client.track("product_viewed", { product: { id: "P1" } });
    time = 2000;
    h.client.track("product_viewed", { product: { id: "P1" } });
    expect(out.warn).toHaveBeenCalledTimes(1);
    expect(out.warn.mock.calls[0][0]).toMatch(/twice within 500ms for product P1/);
  });

  it("warns about values the server will reject, which the lean core lets through", () => {
    const out = logger();
    const h = harness([debug({ logger: out })]);
    h.client.trackUntyped("product_added_to_cart", { product: { id: "P1", quantity: -1 } });
    h.client.trackUntyped("add_to_cart", { product: { id: "P1" } });
    const warnings = out.warn.mock.calls.map((c) => String(c[0]));
    expect(warnings.some((w) => /data.product.quantity: quantity must be greater than 0/.test(w))).toBe(true);
    expect(warnings.some((w) => /"add_to_cart" will be rejected.*data.product.quantity/.test(w))).toBe(true);
  });

  it("logs each sent event", () => {
    const out = logger();
    const h = harness([debug({ logger: out })]);
    h.client.track("page_viewed");
    expect(out.groupCollapsed).toHaveBeenCalledWith("[omnirec] page_viewed");
  });
});
