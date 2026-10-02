// SPDX-License-Identifier: Apache-2.0
"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { OmnirecProvider, useOmnirec, type OmnirecProviderProps } from "@omnirec/commerce-react";
import { autocapture, type AutocaptureOptions } from "@omnirec/commerce-web/autocapture";
import { dom } from "@omnirec/commerce-web/dom";
import { impressions } from "@omnirec/commerce-web/impressions";

export interface OmnirecNextProviderProps extends Omit<OmnirecProviderProps, "children"> {
  children: ReactNode;
  /**
   * Send `page_viewed` on every App Router navigation. Default true. Uses the
   * router's pathname, so it is not confused by Next's internal history calls.
   */
  trackRoutes?: boolean;
  /** Home path for `home_page_viewed`. Default "/". Null disables it. */
  homePath?: string | null;
  /**
   * Install the dom, impressions and autocapture plugins. Default true.
   * Autocapture's own page views are turned off: route tracking replaces them.
   */
  plugins?: OmnirecProviderProps["plugins"];
  autocapture?: AutocaptureOptions | false;
  htmlAttributes?: boolean;
}

function RouteTracker({ homePath }: { homePath: string | null }) {
  const client = useOmnirec();
  const pathname = usePathname();
  const last = useRef<string | null>(null);
  useEffect(() => {
    if (!client || pathname === null || pathname === last.current) return;
    last.current = pathname;
    client.track("page_viewed");
    if (homePath !== null && pathname === homePath) client.track("home_page_viewed");
    // Tell the impressions plugin a new page began, so elements count again.
    window.dispatchEvent(new CustomEvent("omnirec:pageview"));
  }, [client, pathname, homePath]);
  return null;
}

/**
 * Drop-in provider for the App Router. Put it in `app/layout.tsx`:
 *
 *     <OmnirecNextProvider endpoint="/omnirec">{children}</OmnirecNextProvider>
 *
 * Tracks page views on navigation, and by default enables HTML attributes,
 * impressions and autocapture (campaigns, scroll depth, product pages).
 */
export function OmnirecNextProvider({
  children,
  trackRoutes = true,
  homePath = "/",
  plugins,
  autocapture: autocaptureOptions = {},
  htmlAttributes = true,
  ...config
}: OmnirecNextProviderProps) {
  const installed = useRef<NonNullable<OmnirecProviderProps["plugins"]> | null>(null);
  if (!installed.current) {
    installed.current = [
      ...(htmlAttributes ? [dom(), impressions()] : []),
      ...(autocaptureOptions === false
        ? []
        : [autocapture({ ...autocaptureOptions, pageViews: trackRoutes ? false : autocaptureOptions.pageViews, homePath: trackRoutes ? null : homePath })]),
      ...(plugins ?? []),
    ];
  }
  return (
    <OmnirecProvider {...config} plugins={installed.current}>
      {trackRoutes ? <RouteTracker homePath={homePath} /> : null}
      {children}
    </OmnirecProvider>
  );
}

export { useOmnirec, useTrack, useImpression, Track } from "@omnirec/commerce-react";
