"use client";

import { createContext, useContext, useEffect, useRef, type ReactNode } from "react";
import { CommerceClient, type CommerceConfig } from "@omnirec/commerce-web";

const CommerceContext = createContext<CommerceClient | null>(null);

export interface CommerceProviderProps extends CommerceConfig {
  children: ReactNode;
}

/**
 * Wrap the app once. Creates a single {@link CommerceClient} for the lifetime
 * of the provider — identity, batching, and dwell tracking all live on it.
 *
 * This package is a thin binding on purpose: every behaviour lives in
 * `@omnirec/commerce-web`, which has no React dependency at all. A Vue, Svelte,
 * or vanilla integration needs a file this size and nothing more.
 */
export function CommerceProvider({ children, ...config }: CommerceProviderProps) {
  const clientRef = useRef<CommerceClient | null>(null);
  if (!clientRef.current) {
    clientRef.current = new CommerceClient(config);
  }

  useEffect(() => {
    const client = clientRef.current;
    return () => {
      // Flushes anything buffered and detaches listeners, so a hot reload or
      // route teardown doesn't leak timers or lose the last events.
      client?.destroy();
      clientRef.current = null;
    };
  }, []);

  return <CommerceContext.Provider value={clientRef.current}>{children}</CommerceContext.Provider>;
}

/**
 * The client, with every tracker on it:
 *
 *     const commerce = useCommerce();
 *     commerce.product.viewed({ productId: "p123" });
 */
export function useCommerce(): CommerceClient {
  const client = useContext(CommerceContext);
  if (!client) {
    throw new Error("useCommerce() must be called inside <CommerceProvider>");
  }
  return client;
}
