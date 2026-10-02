// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { createOmnirec } from "../src/core/omnirec";

/**
 * Compile-time contract of `track()`. Each `@ts-expect-error` line must fail to
 * typecheck; if a change makes one compile, `npm run typecheck` fails here.
 * The runtime part only proves the file was loaded.
 */
declare module "../src/core/omnirec" {
  interface OmnirecCustomEvents {
    newsletter_popup_closed: { secondsOpen: number; subscribed: boolean };
  }
}

// Never executed: only typechecked.
function typeContract(omnirec: ReturnType<typeof createOmnirec>) {
  // Standard events with their required fields compile.
  omnirec.track("product_added_to_cart", { product: { id: "P1", quantity: 1 } });
  omnirec.track("purchase_completed", {
    order: { id: "o1", currency: "USD", total: "10.00", items: [{ productId: "P1", quantity: 1 }] },
  });
  // Events without data need no argument.
  omnirec.track("page_viewed");
  // A declared custom event compiles.
  omnirec.track("newsletter_popup_closed", { secondsOpen: 4, subscribed: false });

  // @ts-expect-error misspelled event name
  omnirec.track("add_to_card", {});
  // @ts-expect-error quantity is required for add-to-cart
  omnirec.track("product_added_to_cart", { product: { id: "P1" } });
  // @ts-expect-error quantity must be a number
  omnirec.track("product_added_to_cart", { product: { id: "P1", quantity: "one" } });
  // @ts-expect-error data is required when the event requires fields
  omnirec.track("product_viewed");
  // @ts-expect-error a block the event does not carry
  omnirec.track("product_viewed", { product: { id: "P1" }, order: { id: "o1" } });
  // @ts-expect-error custom event fields are typed too
  omnirec.track("newsletter_popup_closed", { secondsOpen: "4", subscribed: false });
}

describe("track() types", () => {
  it("is a compile-time contract", () => {
    expect(typeof typeContract).toBe("function");
  });
});
