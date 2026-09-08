"use client";

import type { ReactNode } from "react";
import { OmnirecProvider } from "@omnirec/react";

/**
 * The entire frontend integration surface for a new adopter — everything
 * else (dwell time, scroll depth, cart abandonment) is automatic once these
 * plugins are listed. See IMPLEMENTATION_PLAN.md's Phase 1 exit criteria.
 */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <OmnirecProvider
      tenantId={process.env.NEXT_PUBLIC_OMNIREC_TENANT_ID ?? "demo-store"}
      endpoint={process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT ?? "http://localhost:8080"}
      plugins={["dwellTime", "scrollDepth", "cart", "wishlist"]}
      requireConsent={false}
    >
      {children}
    </OmnirecProvider>
  );
}
