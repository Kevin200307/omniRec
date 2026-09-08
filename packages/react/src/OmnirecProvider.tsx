import { createContext, useEffect, useMemo, useRef, type ReactNode } from "react";
import { Tracker, type OmnirecConfig } from "@omnirec/core";

export interface OmnirecContextValue {
  tracker: Tracker;
  tenantId: string;
  endpoint: string;
}

export const OmnirecContext = createContext<OmnirecContextValue | null>(null);

export interface OmnirecProviderProps extends OmnirecConfig {
  children: ReactNode;
}

/**
 * Wraps the app once. Instantiates a single Tracker for the lifetime of the
 * provider — plugins, identity, and the event queue all live on it.
 */
export function OmnirecProvider({ children, ...config }: OmnirecProviderProps) {
  const trackerRef = useRef<Tracker | null>(null);
  if (!trackerRef.current) {
    trackerRef.current = new Tracker(config);
  }

  useEffect(() => {
    return () => {
      trackerRef.current?.destroy();
      trackerRef.current = null;
    };
  }, []);

  const value = useMemo<OmnirecContextValue>(
    () => ({ tracker: trackerRef.current!, tenantId: config.tenantId, endpoint: config.endpoint }),
    [config.tenantId, config.endpoint]
  );

  return <OmnirecContext.Provider value={value}>{children}</OmnirecContext.Provider>;
}
