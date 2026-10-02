// SPDX-License-Identifier: Apache-2.0
"use client";

import {
  Children,
  cloneElement,
  isValidElement,
  useCallback,
  useEffect,
  useRef,
  type ReactElement,
  type RefObject,
  type SyntheticEvent,
} from "react";
import type { OmnirecClient, TrackOptions } from "@omnirec/commerce-web";
import { useOmnirec } from "./OmnirecProvider";

/** `track` with the client's exact, catalog-typed signature. */
export type TrackFunction = OmnirecClient["track"];

/**
 * A stable `track` function. Its identity never changes, so it is safe in
 * dependency arrays. During server rendering it does nothing.
 *
 *     const track = useTrack();
 *     track("product_added_to_cart", { product: { id, quantity: 1 } });
 */
export function useTrack(): TrackFunction {
  const client = useOmnirec();
  const clientRef = useRef(client);
  clientRef.current = client;
  return useCallback(
    ((event: string, ...args: unknown[]) =>
      (clientRef.current?.track as (event: string, ...rest: unknown[]) => string | undefined)?.(event, ...args)) as TrackFunction,
    []
  );
}

export interface ImpressionOptions extends TrackOptions {
  /** Share of the element that must be visible. Default 0.5. */
  threshold?: number;
  /** How long it must stay visible, in ms. Default 1000. */
  minVisibleMs?: number;
}

/**
 * Sends `event` once when the element behind `ref` has been visible long
 * enough, for as long as the component is mounted with the same data.
 *
 *     const ref = useRef<HTMLDivElement>(null);
 *     useImpression(ref, "product_list_viewed", { list: { id: "home", productIds } });
 */
export function useImpression(
  ref: RefObject<Element>,
  event: string,
  data: Record<string, unknown> = {},
  options: ImpressionOptions = {}
): void {
  const client = useOmnirec();
  const key = JSON.stringify([event, data]);
  const { threshold = 0.5, minVisibleMs = 1000, ...trackOptions } = options;
  const latest = useRef({ data, trackOptions });
  latest.current = { data, trackOptions };

  useEffect(() => {
    const element = ref.current;
    if (!client || !element || typeof IntersectionObserver === "undefined") return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let sent = false;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (sent) return;
        if (entry.isIntersecting && entry.intersectionRatio >= threshold) {
          timer ??= setTimeout(() => {
            sent = true;
            observer.disconnect();
            client.trackUntyped(event, latest.current.data, latest.current.trackOptions);
          }, minVisibleMs);
        } else if (timer !== undefined) {
          clearTimeout(timer);
          timer = undefined;
        }
      },
      { threshold: [0, threshold] }
    );
    observer.observe(element);
    return () => {
      observer.disconnect();
      if (timer !== undefined) clearTimeout(timer);
    };
    // Re-arm when the event or its data change; option objects are read through the ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, key, threshold, minVisibleMs]);
}

export interface TrackProps {
  /** Event name, from the catalog or your plan. */
  event: string;
  data?: Record<string, unknown>;
  options?: TrackOptions;
  /** DOM event that sends it. Default "click". */
  on?: "click" | "submit" | "change";
  /** Exactly one element; its own handler for the same event still runs. */
  children: ReactElement;
}

const HANDLER = { click: "onClick", submit: "onSubmit", change: "onChange" } as const;

/**
 * Tracks an event when its single child is clicked (or submitted, or changed),
 * without touching the child's own handler.
 *
 *     <Track event="product_clicked" data={{ product: { id } }}>
 *       <a href={url}>{name}</a>
 *     </Track>
 */
export function Track({ event, data = {}, options, on = "click", children }: TrackProps) {
  const client = useOmnirec();
  const child = Children.only(children);
  if (!isValidElement(child)) return child;
  const prop = HANDLER[on];
  const existing = (child.props as Record<string, unknown>)[prop] as ((e: SyntheticEvent) => void) | undefined;
  return cloneElement(child as ReactElement<Record<string, unknown>>, {
    [prop]: (domEvent: SyntheticEvent) => {
      client?.trackUntyped(event, data, options);
      existing?.(domEvent);
    },
  });
}
