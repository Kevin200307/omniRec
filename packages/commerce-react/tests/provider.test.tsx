// SPDX-License-Identifier: Apache-2.0
import { StrictMode, useEffect } from "react";
import { act, cleanup, render, renderHook } from "@testing-library/react";
import { CommerceProvider, useCommerce, useOmnirec, useProductView } from "../src";

interface SentEvent {
  event: string;
  data?: { product?: { id?: string } };
}

function fakeFetch() {
  const sent: SentEvent[] = [];
  const fetchImpl = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body ?? "{}"));
    sent.push(...(body.events ?? []));
    return new Response(JSON.stringify({ accepted: body.events?.length ?? 0 }), { status: 202 });
  });
  return { sent, fetchImpl: fetchImpl as unknown as typeof fetch };
}

function baseConfig(fetchImpl: typeof fetch, onError = vi.fn()) {
  return {
    apiKey: "pk_test_react",
    endpoint: "https://events.test",
    fetchImpl,
    onError,
    autoTrackSessions: false,
    autoTrackDwellTime: false,
  };
}

afterEach(() => {
  cleanup();
  document.cookie.split(";").forEach((c) => {
    document.cookie = `${c.split("=")[0].trim()}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });
  localStorage.clear();
  sessionStorage.clear();
});

describe("CommerceProvider", () => {
  it("throws a clear error when useCommerce is used outside the provider", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => renderHook(() => useCommerce())).toThrow(/inside <CommerceProvider>/);
    expect(() => renderHook(() => useOmnirec())).toThrow(/inside <OmnirecProvider>/);
    spy.mockRestore();
  });

  it("provides one client that sends tracked events", async () => {
    const { sent, fetchImpl } = fakeFetch();
    let client: ReturnType<typeof useCommerce> | undefined;

    function Probe() {
      client = useCommerce();
      return null;
    }

    render(
      <CommerceProvider {...baseConfig(fetchImpl)}>
        <Probe />
      </CommerceProvider>
    );

    await act(async () => {
      client!.product.clicked({ productId: "p1" });
      await client!.flush();
    });

    expect(sent.map((e) => e.event)).toEqual(["product_clicked"]);
    expect(sent[0].data?.product?.id).toBe("p1");
  });

  it("useProductView emits product_viewed on mount and again when the product changes", async () => {
    const { sent, fetchImpl } = fakeFetch();
    let client: ReturnType<typeof useCommerce> | undefined;

    function Product({ id }: { id: string }) {
      client = useCommerce();
      useProductView({ productId: id });
      return null;
    }

    const config = baseConfig(fetchImpl);
    const { rerender } = render(
      <CommerceProvider {...config}>
        <Product id="p1" />
      </CommerceProvider>
    );
    rerender(
      <CommerceProvider {...config}>
        <Product id="p1" />
      </CommerceProvider>
    );
    rerender(
      <CommerceProvider {...config}>
        <Product id="p2" />
      </CommerceProvider>
    );

    await act(async () => {
      await client!.flush();
    });

    const views = sent.filter((e) => e.event === "product_viewed").map((e) => e.data?.product?.id);
    expect(views).toEqual(["p1", "p2"]);
  });

  // Regression test for the bug found in Phase 0: Strict Mode runs effects
  // twice in development (Next.js enables it by default), and the provider used
  // to destroy its client in the first cleanup, dropping every later event.
  it("keeps a working client under React StrictMode", async () => {
    const { sent, fetchImpl } = fakeFetch();
    const onError = vi.fn();
    let client: ReturnType<typeof useCommerce> | undefined;

    function Probe() {
      const c = useCommerce();
      useEffect(() => {
        client = c;
      }, [c]);
      return null;
    }

    render(
      <StrictMode>
        <CommerceProvider {...baseConfig(fetchImpl, onError)}>
          <Probe />
        </CommerceProvider>
      </StrictMode>
    );

    await act(async () => {
      client!.product.clicked({ productId: "p1" });
      await client!.flush();
    });

    expect(onError).not.toHaveBeenCalled();
    expect(sent.map((e) => e.event)).toEqual(["product_clicked"]);
  });
});
