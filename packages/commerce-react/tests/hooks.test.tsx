// SPDX-License-Identifier: Apache-2.0
import { StrictMode, useEffect, useRef } from "react";
import { renderToString } from "react-dom/server";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { OmnirecProvider, Track, useImpression, useOmnirec, useTrack } from "../src";
import type { OmnirecClient } from "@omnirec/commerce-web";

interface Sent {
  event: string;
  data: Record<string, unknown>;
}

function setup() {
  const sent: Sent[] = [];
  const fetchImpl = (async (_url: string, init?: RequestInit) => {
    sent.push(...JSON.parse(String(init?.body)).events);
    return new Response(null, { status: 202 });
  }) as unknown as typeof fetch;
  const config = { endpoint: "https://events.test", fetchImpl, autoTrackSessions: false, onError: vi.fn() };
  let client: OmnirecClient | null = null;
  function Capture() {
    const c = useOmnirec();
    useEffect(() => {
      client = c;
    }, [c]);
    return null;
  }
  return {
    sent,
    config,
    Capture,
    async flush() {
      await act(async () => {
        await client?.flush();
      });
      return sent;
    },
  };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("useTrack", () => {
  it("returns a stable function that tracks", async () => {
    const h = setup();
    const seen: unknown[] = [];
    function Button() {
      const track = useTrack();
      seen.push(track);
      return <button onClick={() => track("product_added_to_cart", { product: { id: "P1", quantity: 1 } })}>add</button>;
    }
    const { getByText, rerender } = render(
      <OmnirecProvider {...h.config}>
        <h.Capture />
        <Button />
      </OmnirecProvider>
    );
    rerender(
      <OmnirecProvider {...h.config}>
        <h.Capture />
        <Button />
      </OmnirecProvider>
    );
    fireEvent.click(getByText("add"));
    expect(seen[0]).toBe(seen[1]);
    expect((await h.flush()).map((e) => e.event)).toEqual(["product_added_to_cart"]);
  });

  it("keeps working under StrictMode", async () => {
    const h = setup();
    function Button() {
      const track = useTrack();
      return <button onClick={() => track("page_viewed")}>go</button>;
    }
    const { getByText } = render(
      <StrictMode>
        <OmnirecProvider {...h.config}>
          <h.Capture />
          <Button />
        </OmnirecProvider>
      </StrictMode>
    );
    // Let any deferred teardown from the double effect run.
    await act(async () => new Promise((r) => setTimeout(r, 5)));
    fireEvent.click(getByText("go"));
    expect((await h.flush()).map((e) => e.event)).toEqual(["page_viewed"]);
    expect(h.config.onError).not.toHaveBeenCalled();
  });
});

describe("<Track>", () => {
  it("tracks on click and still runs the child's own handler", async () => {
    const h = setup();
    const own = vi.fn();
    const { getByText } = render(
      <OmnirecProvider {...h.config}>
        <h.Capture />
        <Track event="product_clicked" data={{ product: { id: "P7" } }}>
          <a href="#p7" onClick={own}>shoe</a>
        </Track>
      </OmnirecProvider>
    );
    fireEvent.click(getByText("shoe"));
    expect(own).toHaveBeenCalledTimes(1);
    const [event] = await h.flush();
    expect(event).toMatchObject({ event: "product_clicked", data: { product: { id: "P7" } } });
  });

  it("supports submit", async () => {
    const h = setup();
    const { container } = render(
      <OmnirecProvider {...h.config}>
        <h.Capture />
        <Track event="search_performed" data={{ search: { query: "shoes" } }} on="submit">
          <form onSubmit={(e) => e.preventDefault()}>
            <button>go</button>
          </form>
        </Track>
      </OmnirecProvider>
    );
    fireEvent.submit(container.querySelector("form")!);
    expect((await h.flush()).map((e) => e.event)).toEqual(["search_performed"]);
  });
});

describe("useImpression", () => {
  it("tracks once the element has been visible long enough", async () => {
    let fire: ((ratio: number) => void) | undefined;
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(private readonly cb: IntersectionObserverCallback) {
          fire = (ratio) =>
            this.cb([{ isIntersecting: ratio > 0, intersectionRatio: ratio } as IntersectionObserverEntry], this as never);
        }
        observe() {}
        disconnect() {}
      }
    );
    vi.useFakeTimers();
    const h = setup();
    function Row() {
      const ref = useRef<HTMLDivElement>(null);
      useImpression(ref, "product_list_viewed", { list: { id: "home", productIds: ["a"] } });
      return <div ref={ref}>row</div>;
    }
    render(
      <OmnirecProvider {...h.config}>
        <h.Capture />
        <Row />
      </OmnirecProvider>
    );
    act(() => fire!(1));
    act(() => vi.advanceTimersByTime(1000));
    act(() => fire!(1));
    act(() => vi.advanceTimersByTime(1000));
    vi.useRealTimers();
    const events = await h.flush();
    expect(events.map((e) => e.event)).toEqual(["product_list_viewed"]);
  });
});

describe("server rendering", () => {
  it("renders without creating a client and without throwing", () => {
    const realWindow = globalThis.window;
    const fetchImpl = vi.fn();
    function Page() {
      const track = useTrack();
      const client = useOmnirec();
      track("page_viewed"); // a no-op on the server
      return <p>{client === null ? "server" : "browser"}</p>;
    }
    // Simulate a server: no window.
    // @ts-expect-error deliberately removing the global for this render
    delete globalThis.window;
    try {
      const html = renderToString(
        <OmnirecProvider endpoint="https://events.test" fetchImpl={fetchImpl as unknown as typeof fetch}>
          <Page />
        </OmnirecProvider>
      );
      expect(html).toContain("server");
      expect(fetchImpl).not.toHaveBeenCalled();
    } finally {
      globalThis.window = realWindow;
    }
  });
});
