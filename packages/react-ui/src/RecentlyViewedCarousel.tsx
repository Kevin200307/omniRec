import { useEffect, useState } from "react";
import { useOmnirec } from "@omnirec/react";
import { omnirecFetch } from "./api";

export interface RecentlyViewedItem {
  productId: string;
  viewedAt?: string;
  [key: string]: unknown;
}

interface RecentlyViewedResponse {
  items: RecentlyViewedItem[];
}

export interface RecentlyViewedCarouselProps {
  userId: string;
  renderItem: (item: RecentlyViewedItem) => React.ReactNode;
  className?: string;
}

/**
 * Backed by CacheProvider (Redis in the default starter) via
 * /v1/recently-viewed — works standalone with no recommendation provider
 * configured at all, proving the cache abstraction doesn't secretly depend
 * on a recommendation provider being present.
 */
export function RecentlyViewedCarousel({ userId, renderItem, className }: RecentlyViewedCarouselProps) {
  const { endpoint, tenantId } = useOmnirec();
  const [items, setItems] = useState<RecentlyViewedItem[]>([]);

  useEffect(() => {
    let cancelled = false;
    omnirecFetch<RecentlyViewedResponse>(endpoint, "/v1/recently-viewed", { tenantId, userId })
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

  if (items.length === 0) return null;

  return <div className={className}>{items.map((item) => renderItem(item))}</div>;
}
