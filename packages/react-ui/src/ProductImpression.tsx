// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { useOmnirec } from "@omnirec/react";

export interface ProductImpressionProps {
  productId: string;
  children: ReactNode;
  className?: string;
  /** Fires alongside the automatic PRODUCT_CLICKED tracking — for app-specific behavior like navigating to a detail page. */
  onClick?: () => void;
}

/**
 * Wrap a product card with this and click + dwell time are captured
 * automatically — dwell time via @omnirec/core's dwellTimePlugin, which
 * finds this element by its data-omnirec-product-id attribute. No manual
 * IntersectionObserver wiring needed in the host app.
 */
export function ProductImpression({ productId, children, className, onClick }: ProductImpressionProps) {
  const { track } = useOmnirec();

  return (
    <div
      data-omnirec-product-id={productId}
      className={className}
      onClick={() => {
        track("PRODUCT_CLICKED", "IMPLICIT", { productId });
        onClick?.();
      }}
    >
      {children}
    </div>
  );
}
