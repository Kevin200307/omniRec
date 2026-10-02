// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent } from "../events/types";

/**
 * Sees every event on its way to the batch. Call `next(event)` to pass it on
 * (modified or not); not calling it drops the event; calling it later delays
 * it. Must not throw — if it does, the event is dropped and the error goes to
 * `onError`, and the queue keeps working.
 */
export type Middleware = (event: CommerceEvent, next: (event: CommerceEvent) => void) => void;

/** What a plugin can reach on the client it is installed into. */
export interface PluginHost {
  track(event: string, data?: Record<string, unknown>, options?: { properties?: Record<string, unknown> }): string | undefined;
  readonly config: { readonly endpoint: string; readonly debug: boolean };
  reportError(error: Error): void;
}

/**
 * An opt-in capability: HTML attributes, autocapture, consent, debug output.
 *
 * - `setup` runs once when the plugin is installed and may return a teardown.
 * - `middleware` joins the pipeline. `phase: "before-validate"` runs before the
 *   client-side check (consent uses it to hold events); the default
 *   `"after-validate"` runs on valid events only.
 */
export interface OmnirecPlugin {
  name: string;
  setup?(host: PluginHost): void | (() => void);
  middleware?: Middleware;
  phase?: "before-validate" | "after-validate";
}

/**
 * Runs `event` through `stages` in order, then hands it to `sink`. A throwing
 * stage drops the event and reports the error; it never breaks later events.
 */
export function runPipeline(
  event: CommerceEvent,
  stages: readonly Middleware[],
  sink: (event: CommerceEvent) => void,
  onError: (error: Error) => void
): void {
  const step = (index: number, current: CommerceEvent): void => {
    if (index === stages.length) {
      sink(current);
      return;
    }
    let called = false;
    try {
      stages[index](current, (next) => {
        if (called) return; // a stage calling next twice must not duplicate the event
        called = true;
        step(index + 1, next);
      });
    } catch (error) {
      onError(error instanceof Error ? error : new Error(String(error)));
    }
  };
  step(0, event);
}
