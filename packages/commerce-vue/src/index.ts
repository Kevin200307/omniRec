// SPDX-License-Identifier: Apache-2.0
import { inject, type App, type Directive, type InjectionKey } from "vue";
import { OmnirecClient, type OmnirecConfig, type TrackOptions } from "@omnirec/commerce-web";

export const OMNIREC_KEY: InjectionKey<OmnirecClient | null> = Symbol("omnirec");

export interface OmnirecVueOptions extends OmnirecConfig {
  /** Use an existing client instead of creating one. */
  client?: OmnirecClient;
}

/** `[event, data?, options?]`, the value of `v-track` and `v-impression`. */
export type TrackBinding = [event: string, data?: Record<string, unknown>, options?: TrackOptions];

const isBrowser = () => typeof window !== "undefined" && typeof document !== "undefined";

interface TrackState {
  binding: TrackBinding;
  trigger: string;
  handler: (event: Event) => void;
}
interface ImpressionState {
  binding: TrackBinding;
  key: string;
  observer?: IntersectionObserver;
  timer?: ReturnType<typeof setTimeout>;
  sent: boolean;
}

const trackStates = new WeakMap<Element, TrackState>();
const impressionStates = new WeakMap<Element, ImpressionState>();

function trackDirective(client: OmnirecClient | null): Directive<Element, TrackBinding> {
  return {
    mounted(el, { value, arg }) {
      if (!client) return;
      const state: TrackState = {
        binding: value,
        trigger: arg ?? "click",
        handler: () => {
          const [event, data, options] = state.binding;
          client.trackUntyped(event, data ?? {}, options ?? {});
        },
      };
      trackStates.set(el, state);
      el.addEventListener(state.trigger, state.handler);
    },
    updated(el, { value }) {
      const state = trackStates.get(el);
      if (state) state.binding = value;
    },
    beforeUnmount(el) {
      const state = trackStates.get(el);
      if (state) el.removeEventListener(state.trigger, state.handler);
      trackStates.delete(el);
    },
  };
}

function impressionDirective(
  client: OmnirecClient | null,
  { threshold = 0.5, minVisibleMs = 1000 } = {}
): Directive<Element, TrackBinding> {
  const arm = (el: Element, state: ImpressionState) => {
    if (!client || typeof IntersectionObserver === "undefined") return;
    state.observer = new IntersectionObserver(
      ([entry]) => {
        if (state.sent) return;
        if (entry.isIntersecting && entry.intersectionRatio >= threshold) {
          state.timer ??= setTimeout(() => {
            state.sent = true;
            state.observer?.disconnect();
            const [event, data, options] = state.binding;
            client.trackUntyped(event, data ?? {}, options ?? {});
          }, minVisibleMs);
        } else if (state.timer !== undefined) {
          clearTimeout(state.timer);
          state.timer = undefined;
        }
      },
      { threshold: [0, threshold] }
    );
    state.observer.observe(el);
  };
  const disarm = (state: ImpressionState) => {
    state.observer?.disconnect();
    if (state.timer !== undefined) clearTimeout(state.timer);
    state.timer = undefined;
  };
  return {
    mounted(el, { value }) {
      const state: ImpressionState = { binding: value, key: JSON.stringify(value), sent: false };
      impressionStates.set(el, state);
      arm(el, state);
    },
    updated(el, { value }) {
      const state = impressionStates.get(el);
      if (!state) return;
      const key = JSON.stringify(value);
      state.binding = value;
      if (key !== state.key) {
        // Different event or data: a new impression to count.
        state.key = key;
        state.sent = false;
        disarm(state);
        arm(el, state);
      }
    },
    beforeUnmount(el) {
      const state = impressionStates.get(el);
      if (state) disarm(state);
      impressionStates.delete(el);
    },
  };
}

/**
 * Installs OmniRec in a Vue 3 app:
 *
 *     app.use(OmnirecPlugin, { endpoint: "/omnirec", plugins: [autocapture()] });
 *
 * Then, in templates:
 *
 *     <button v-track="['product_added_to_cart', { product: { id, quantity: 1 } }]">Add</button>
 *     <form v-track:submit="['search_performed', { search: { query } }]">...</form>
 *     <section v-impression="['product_list_viewed', { list: { id: 'home', productIds } }]">...</section>
 *
 * During server rendering (Nuxt) no client is created and nothing is tracked.
 */
export const OmnirecPlugin = {
  install(app: App, options: OmnirecVueOptions) {
    const { client: external, ...config } = options;
    const client = external ?? (isBrowser() ? new OmnirecClient(config) : null);
    app.provide(OMNIREC_KEY, client);
    app.config.globalProperties.$omnirec = client;
    app.directive("track", trackDirective(client));
    app.directive("impression", impressionDirective(client));
    if (client && !external) {
      const unmount = app.unmount.bind(app);
      app.unmount = () => {
        unmount();
        client.destroy();
      };
    }
  },
};

/** The client in a component's `setup()`, or `null` during server rendering. */
export function useOmnirec(): OmnirecClient | null {
  const client = inject(OMNIREC_KEY, undefined);
  if (client === undefined) {
    throw new Error("useOmnirec() needs app.use(OmnirecPlugin, { endpoint })");
  }
  return client;
}

declare module "vue" {
  interface ComponentCustomProperties {
    $omnirec: OmnirecClient | null;
  }
}
