// SPDX-License-Identifier: Apache-2.0
"use client";

import { createContext, useContext, useEffect, useRef, type ReactNode } from "react";
import { CommerceClient, OmnirecClient, type CommerceConfig } from "@omnirec/commerce-web";

/**
 * `undefined` means "no provider above this component", which is a developer
 * mistake worth a clear error. `{ client: null }` means "provider present, but
 * rendering on the server", where tracking is a silent no-op.
 */
const OmnirecContext = createContext<{ client: OmnirecClient | null } | undefined>(undefined);

export interface OmnirecProviderProps extends CommerceConfig {
  children: ReactNode;
  /** Use an existing client instead of creating one. The provider will not destroy it. */
  client?: OmnirecClient;
}

const isBrowser = () => typeof window !== "undefined" && typeof document !== "undefined";

function useClient(
  props: Omit<OmnirecProviderProps, "children">,
  create: (config: CommerceConfig) => OmnirecClient,
  createOnServer = false
) {
  const { client: external, ...config } = props;
  const ref = useRef<OmnirecClient | null>(external ?? null);
  const pendingDestroy = useRef<ReturnType<typeof setTimeout> | null>(null);
  if (!ref.current && (createOnServer || isBrowser())) ref.current = create(config);

  useEffect(() => {
    // React Strict Mode runs effects twice in development: mount, cleanup,
    // mount. Destroying in the cleanup would leave the app holding a dead
    // client, so the teardown is deferred and a remount cancels it. A real
    // unmount still destroys the client on the next tick.
    if (pendingDestroy.current) {
      clearTimeout(pendingDestroy.current);
      pendingDestroy.current = null;
    }
    const client = ref.current;
    return () => {
      if (!client || external) return;
      pendingDestroy.current = setTimeout(() => {
        pendingDestroy.current = null;
        client.destroy();
        if (ref.current === client) ref.current = null;
      }, 0);
    };
  }, [external]);

  return ref.current;
}

/**
 * Wrap the app once. Creates one client for the lifetime of the provider and
 * makes it available to `useOmnirec`, `useTrack`, `useImpression` and `<Track>`.
 * Safe to render on the server: no client is created there and tracking calls
 * do nothing.
 *
 *     <OmnirecProvider endpoint="/omnirec" plugins={[dom(), autocapture()]}>
 *       <App />
 *     </OmnirecProvider>
 */
export function OmnirecProvider({ children, ...props }: OmnirecProviderProps) {
  const client = useClient(props, (config) => new OmnirecClient(config));
  return <OmnirecContext.Provider value={{ client }}>{children}</OmnirecContext.Provider>;
}

/**
 * The same provider, creating a client that also has the v1 per-event helper
 * methods (`commerce.cart.productAdded(...)`) for existing integrations.
 *
 * @deprecated use `OmnirecProvider` and `useTrack()`.
 */
export function CommerceProvider({ children, ...props }: OmnirecProviderProps) {
  // v1 behaviour, kept for existing integrations: the client also exists during
  // server rendering, so render-time calls such as getIdentity() keep working.
  const client = useClient(props, (config) => new CommerceClient(config), true);
  return <OmnirecContext.Provider value={{ client }}>{children}</OmnirecContext.Provider>;
}

export type CommerceProviderProps = OmnirecProviderProps;

/** The client, or `null` while rendering on the server. Throws outside a provider. */
export function useOmnirec(): OmnirecClient | null {
  const context = useContext(OmnirecContext);
  if (!context) {
    throw new Error("useOmnirec() must be called inside <OmnirecProvider>");
  }
  return context.client;
}

/**
 * The client with the v1 per-event helpers. Requires `<CommerceProvider>`.
 *
 * @deprecated use `useTrack()`.
 */
export function useCommerce(): CommerceClient {
  const context = useContext(OmnirecContext);
  if (!context) {
    throw new Error("useCommerce() must be called inside <CommerceProvider>");
  }
  if (context.client && !(context.client instanceof CommerceClient)) {
    throw new Error("useCommerce() needs <CommerceProvider>; with <OmnirecProvider> use useTrack()");
  }
  return context.client as CommerceClient;
}
