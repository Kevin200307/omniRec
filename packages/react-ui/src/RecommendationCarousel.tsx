// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useOmnirec } from "@omnirec/react";
import { omnirecFetch } from "./api";

export interface RecommendedItem {
  productId: string;
  score?: number;
  [key: string]: unknown;
}

interface RecommendationsResponse {
  items: RecommendedItem[];
}

export interface RecommendationCarouselProps {
  userId: string;
  renderItem: (item: RecommendedItem) => React.ReactNode;
  fallback?: React.ReactNode;
  className?: string;
}

/**
 * Calls the backend's /v1/recommendations, whose PersonalizationService
 * facade dispatches to whichever RecommendationProvider(s) are active
 * (Personalize, Google Rec AI, or both) — again, unknown to this component.
 */
export function RecommendationCarousel({ userId, renderItem, fallback = null, className }: RecommendationCarouselProps) {
  const { endpoint, tenantId } = useOmnirec();
  const [items, setItems] = useState<RecommendedItem[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    omnirecFetch<RecommendationsResponse>(endpoint, "/v1/recommendations", { tenantId, userId })
      .then((res) => {
        if (!cancelled) setItems(res.items);
      })
      .catch(() => {
        if (!cancelled) setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, tenantId, userId]);

  if (items === null) return <>{fallback}</>;
  if (items.length === 0) return null;

  return <div className={className}>{items.map((item) => renderItem(item))}</div>;
}
