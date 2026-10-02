// SPDX-License-Identifier: Apache-2.0
import { defineComponent, h, nextTick, ref } from "vue";
import { mount } from "@vue/test-utils";
import { OmnirecPlugin, useOmnirec } from "../src";
import type { OmnirecClient } from "@omnirec/commerce-web";

function setup() {
  const sent: Array<{ event: string; data: Record<string, unknown> }> = [];
  const fetchImpl = (async (_url: string, init?: RequestInit) => {
    sent.push(...JSON.parse(String(init?.body)).events);
    return new Response(null, { status: 202 });
  }) as unknown as typeof fetch;
  return { sent, options: { endpoint: "https://events.test", fetchImpl, autoTrackSessions: false } };
}

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("OmnirecPlugin", () => {
  it("provides the client to setup() and templates", () => {
    const h1 = setup();
    let fromSetup: OmnirecClient | null = null;
    const Probe = defineComponent({
      setup() {
        fromSetup = useOmnirec();
        return () => h("p", "probe");
      },
    });
    const wrapper = mount(Probe, { global: { plugins: [[OmnirecPlugin, h1.options]] } });
    expect(fromSetup).not.toBeNull();
    expect(wrapper.vm.$omnirec).toBe(fromSetup);
  });

  it("explains a missing plugin", () => {
    const Probe = defineComponent({ setup: () => (useOmnirec(), () => h("p")) });
    expect(() => mount(Probe)).toThrow(/OmnirecPlugin/);
  });

  it("v-track sends on click by default and on the named event otherwise, with current data", async () => {
    const h1 = setup();
    const id = ref("P1");
    const Comp = defineComponent({
      template: `
        <div>
          <button id="add" v-track="['product_added_to_cart', { product: { id, quantity: 1 } }]">add</button>
          <form id="search" v-track:submit="['search_performed', { search: { query: 'shoes' } }]" @submit.prevent></form>
        </div>`,
      setup: () => ({ id }),
    });
    const wrapper = mount(Comp, { global: { plugins: [[OmnirecPlugin, h1.options]] } });
    id.value = "P2";
    await nextTick();
    await wrapper.find("#add").trigger("click");
    await wrapper.find("#search").trigger("submit");
    await wrapper.vm.$omnirec!.flush();
    expect(h1.sent.map((e) => e.event)).toEqual(["product_added_to_cart", "search_performed"]);
    expect(h1.sent[0].data).toEqual({ product: { id: "P2", quantity: 1 } });
    wrapper.unmount();
  });

  it("v-impression sends once after the element has been visible long enough", async () => {
    const observers: Array<(ratio: number) => void> = [];
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(cb: IntersectionObserverCallback) {
          observers.push((ratio) =>
            cb([{ isIntersecting: ratio > 0, intersectionRatio: ratio } as IntersectionObserverEntry], this as never)
          );
        }
        observe() {}
        disconnect() {}
      }
    );
    vi.useFakeTimers();
    const h1 = setup();
    const Comp = defineComponent({
      template: `<section v-impression="['product_list_viewed', { list: { id: 'home', productIds: ['a'] } }]">x</section>`,
    });
    const wrapper = mount(Comp, { global: { plugins: [[OmnirecPlugin, h1.options]] } });
    observers[0](1);
    vi.advanceTimersByTime(1000);
    observers[0](1);
    vi.advanceTimersByTime(1000);
    vi.useRealTimers();
    await wrapper.vm.$omnirec!.flush();
    expect(h1.sent.map((e) => e.event)).toEqual(["product_list_viewed"]);
  });
});
