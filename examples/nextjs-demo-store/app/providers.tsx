"use client";

import type { ReactNode } from "react";
import { CommerceProvider } from "@omnirec/commerce-react";

/**
 * The entire frontend integration surface.
 *
 * Note what is here: a publishable key and a URL. No AWS keys, no Google
 * service account, no provider SDK — the browser cannot reach a provider even
 * in principle. Everything else (anonymous id, session, dwell time, batching,
 * retry, offline buffering) is automatic.
 */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <CommerceProvider
      apiKey={process.env.NEXT_PUBLIC_OMNIREC_API_KEY ?? "pk_test_demo_store"}
      endpoint={process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT ?? "http://localhost:8081"}
      tenantId={process.env.NEXT_PUBLIC_OMNIREC_TENANT_ID ?? "demo-store"}
      debug
    >
      {children}
    </CommerceProvider>
  );
}
