// SPDX-License-Identifier: Apache-2.0
import { act, cleanup, render } from "@testing-library/react";
import { useEffect } from "react";
import type { OmnirecClient } from "@omnirec/commerce-web";

let pathname = "/";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

const jar = new Map<string, string>();
vi.mock("next/headers", () => ({
  cookies: () => ({ get: (name: string) => (jar.has(name) ? { name, value: jar.get(name)! } : undefined) }),
}));

const { OmnirecNextProvider, useOmnirec } = await import("../src/index");
const { currentIdentity, omnirecServer, resetOmnirecServer } = await import("../src/server");

afterEach(() => {
  cleanup();
  localStorage.clear();
  resetOmnirecServer();
  jar.clear();
  pathname = "/";
});

describe("OmnirecNextProvider", () => {
  it("tracks a page view per App Router navigation, plus home_page_viewed on the home path", async () => {
    const sent: Array<{ event: string }> = [];
    const fetchImpl = (async (_url: string, init?: RequestInit) => {
      sent.push(...JSON.parse(String(init?.body)).events);
      return new Response(null, { status: 202 });
    }) as unknown as typeof fetch;
    let client: OmnirecClient | null = null;
    function Capture() {
      const c = useOmnirec();
      useEffect(() => {
        client = c;
      }, [c]);
      return null;
    }
    const tree = () => (
      <OmnirecNextProvider endpoint="https://events.test" fetchImpl={fetchImpl} autoTrackSessions={false}
                           autocapture={{ scrollDepth: false }}>
        <Capture />
      </OmnirecNextProvider>
    );
    const { rerender } = render(tree());
    pathname = "/shoes";
    rerender(tree());
    rerender(tree()); // same path: no new view
    await act(async () => {
      await client!.flush();
    });
    expect(sent.map((e) => e.event)).toEqual(["page_viewed", "home_page_viewed", "page_viewed"]);
  });
});

describe("server helpers", () => {
  it("reads the visitor identity from the request cookies", () => {
    jar.set("omnirec_anonymous_id", "anon_A");
    jar.set("omnirec_session_id", "s1");
    expect(currentIdentity("c_1")).toEqual({ anonymousId: "anon_A", sessionId: "s1", userId: "c_1" });
  });

  it("creates one shared sender from the environment", () => {
    process.env.OMNIREC_ENDPOINT = "https://events.test";
    try {
      const first = omnirecServer({ endpoint: "https://events.test", flushIntervalMs: 0 });
      expect(omnirecServer()).toBe(first);
    } finally {
      delete process.env.OMNIREC_ENDPOINT;
    }
  });

  it("explains a missing endpoint", () => {
    expect(() => omnirecServer()).toThrow(/OMNIREC_ENDPOINT/);
  });
});
