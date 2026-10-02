// SPDX-License-Identifier: Apache-2.0
import type { OmnirecPlugin } from "../core/pipeline";
import { collectFields } from "./fields";

export interface DomOptions {
  /** Root to listen on. Default `document`. */
  root?: Document | Element;
}

type Trigger = "click" | "submit" | "change";
const TRIGGERS: readonly Trigger[] = ["click", "submit", "change"];

/** Forms submit, form controls change, everything else clicks — unless `data-omnirec-on` says otherwise. */
function triggerOf(element: Element): Trigger {
  const declared = element.getAttribute("data-omnirec-on");
  if (declared === "click" || declared === "submit" || declared === "change") return declared;
  const tag = element.tagName;
  if (tag === "FORM") return "submit";
  if (tag === "SELECT" || (tag === "INPUT" && !["button", "submit", "reset", "image"].includes((element as HTMLInputElement).type))) {
    return "change";
  }
  return "click";
}

/**
 * Tracks events declared in HTML:
 *
 *     <div data-omnirec-product="P100" data-omnirec-price="12.50">
 *       <button data-omnirec-event="product_added_to_cart" data-omnirec-quantity="1">Add</button>
 *     </div>
 *
 * One delegated listener per trigger on the document, so content rendered later
 * by React, Vue or a server works without rebinding. Fields come from the
 * element and its ancestors (nearest wins); `data-omnirec-props` and
 * `data-omnirec-data` take JSON for anything attributes cannot express.
 */
export function dom(options: DomOptions = {}): OmnirecPlugin {
  return {
    name: "dom",
    setup(host) {
      const root = options.root ?? (typeof document === "undefined" ? undefined : document);
      if (!root) return;

      const handle = (domEvent: Event) => {
        const start = domEvent.target instanceof Element ? domEvent.target : null;
        const element = start?.closest("[data-omnirec-event]");
        if (!element || triggerOf(element) !== domEvent.type) return;
        const name = element.getAttribute("data-omnirec-event");
        if (!name) return;
        const fields = collectFields(element);
        for (const problem of fields.problems) host.reportError(new Error(`[omnirec dom] ${problem} on "${name}"`));
        host.track(name, fields.data, { properties: fields.properties });
      };

      for (const trigger of TRIGGERS) root.addEventListener(trigger, handle);
      return () => {
        for (const trigger of TRIGGERS) root.removeEventListener(trigger, handle);
      };
    },
  };
}
