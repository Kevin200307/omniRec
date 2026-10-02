// SPDX-License-Identifier: Apache-2.0
import type { OmnirecPlugin } from "../core/pipeline";
import { DwellTimeTracker } from "../dwell/dwellTimeTracker";
import type { EventContext } from "../events/types";
import { collectFields } from "./fields";
import { PAGEVIEW_EVENT } from "./impressions";

export interface AutocaptureOptions {
  /** `page_viewed` on load and on every client-side navigation. Default true. */
  pageViews?: boolean;
  /** Also send `home_page_viewed` on this path. Default "/". Set to null to disable. */
  homePath?: string | null;
  /** Attach UTM parameters and ad click ids to every event in the session. Default true. */
  campaigns?: boolean;
  /** `scroll_depth_reached` at 25, 50, 75 and 90 percent, once each per page. Default true. */
  scrollDepth?: boolean;
  /** `product_viewed` when the page declares `data-omnirec-page="product"`. Default true. */
  productPages?: boolean;
  /** Measure foreground time on auto-tracked product pages. Default true. */
  dwellTime?: boolean;
}

type Campaign = NonNullable<EventContext["campaign"]>;

const CAMPAIGN_KEY = "omnirec_campaign";
const CLICK_IDS = ["gclid", "fbclid", "msclkid", "ttclid"] as const;
const SCROLL_THRESHOLDS = [25, 50, 75, 90];

/** UTM parameters and click ids from a query string, or undefined when there are none. */
export function parseCampaign(search: string): Campaign | undefined {
  const params = new URLSearchParams(search);
  const campaign: Campaign = {};
  const map: Array<[string, keyof Campaign]> = [
    ["utm_source", "source"],
    ["utm_medium", "medium"],
    ["utm_campaign", "name"],
    ["utm_term", "term"],
    ["utm_content", "content"],
  ];
  for (const [param, field] of map) {
    const value = params.get(param);
    if (value) (campaign as Record<string, string>)[field] = value.slice(0, 256);
  }
  for (const id of CLICK_IDS) {
    const value = params.get(id);
    if (value) {
      campaign.clickId = value.slice(0, 256);
      campaign.clickIdType = id;
      break;
    }
  }
  return Object.keys(campaign).length ? campaign : undefined;
}

function storedCampaign(): Campaign | undefined {
  try {
    const raw = sessionStorage.getItem(CAMPAIGN_KEY);
    return raw ? (JSON.parse(raw) as Campaign) : undefined;
  } catch {
    return undefined;
  }
}

function storeCampaign(campaign: Campaign): void {
  try {
    sessionStorage.setItem(CAMPAIGN_KEY, JSON.stringify(campaign));
  } catch {
    // storage blocked: the campaign still applies for this page
  }
}

/**
 * Everything the SDK can know without merchant code: page views (including
 * single-page-app navigation), campaign attribution, scroll depth, and product
 * views on pages that declare a product. It never guesses business events such
 * as add to cart from button text or CSS — those are declared with the dom
 * plugin or tracked with `track()`.
 */
export function autocapture(options: AutocaptureOptions = {}): OmnirecPlugin {
  const pageViews = options.pageViews ?? true;
  const homePath = options.homePath === undefined ? "/" : options.homePath;
  const campaigns = options.campaigns ?? true;
  const scrollDepth = options.scrollDepth ?? true;
  const productPages = options.productPages ?? true;
  const dwellTime = options.dwellTime ?? true;

  let campaign: Campaign | undefined;
  let pageType: string | undefined;

  return {
    name: "autocapture",
    phase: "before-validate",
    middleware(event, next) {
      if (!campaign && !pageType) {
        next(event);
        return;
      }
      next({
        ...event,
        context: {
          ...event.context,
          ...(campaign && !event.context.campaign ? { campaign } : {}),
          ...(pageType && !event.context.page ? { page: { type: pageType } } : {}),
        },
      });
    },
    setup(host) {
      if (typeof window === "undefined" || typeof document === "undefined") return;

      if (campaigns) {
        // A new landing with campaign parameters replaces the session's campaign.
        const landed = parseCampaign(location.search);
        if (landed) storeCampaign(landed);
        campaign = landed ?? storedCampaign();
      }

      const dwell = dwellTime
        ? new DwellTimeTracker((productId, dwellTimeMs, _commerce, viewEventId) => {
            host.track("product_viewed", { product: { id: productId } }, {
              properties: viewEventId ? { dwellTimeMs, viewEventId } : { dwellTimeMs },
            });
          })
        : undefined;

      let lastUrl: string | null = null;
      let viewedProducts = new Set<string>();
      let scrollFired = new Set<number>();

      const checkProductPage = () => {
        if (!productPages) return;
        const root = document.querySelector('[data-omnirec-page="product"]');
        if (!root) return;
        const fields = collectFields(root);
        const product = fields.data.product as { id?: unknown } | undefined;
        const id = typeof product?.id === "string" ? product.id : undefined;
        if (!id || viewedProducts.has(id)) return;
        viewedProducts.add(id);
        const eventId = host.track("product_viewed", fields.data, { properties: fields.properties });
        dwell?.start(id, {}, eventId);
      };

      const onPage = () => {
        const url = location.pathname + location.search;
        if (url === lastUrl) return;
        lastUrl = url;
        dwell?.flush();
        viewedProducts = new Set();
        scrollFired = new Set();
        pageType = document.querySelector("[data-omnirec-page]")?.getAttribute("data-omnirec-page") ?? undefined;
        if (pageViews) host.track("page_viewed");
        if (homePath !== null && location.pathname === homePath) host.track("home_page_viewed");
        window.dispatchEvent(new CustomEvent(PAGEVIEW_EVENT));
        checkProductPage();
      };

      // Client-side navigation: wrap the History API and listen for back/forward.
      const originalPush = history.pushState;
      const originalReplace = history.replaceState;
      history.pushState = function (this: History, ...args: Parameters<History["pushState"]>) {
        const result = originalPush.apply(this, args);
        onPage();
        return result;
      };
      history.replaceState = function (this: History, ...args: Parameters<History["replaceState"]>) {
        const result = originalReplace.apply(this, args);
        onPage();
        return result;
      };
      window.addEventListener("popstate", onPage);

      // Product content often renders after navigation; watch for it.
      const mutations =
        productPages && typeof MutationObserver !== "undefined"
          ? new MutationObserver(() => {
              pageType ??= document.querySelector("[data-omnirec-page]")?.getAttribute("data-omnirec-page") ?? undefined;
              checkProductPage();
            })
          : undefined;
      mutations?.observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["data-omnirec-page", "data-omnirec-product", "data-omnirec-product-id"],
      });

      const onScroll = () => {
        const doc = document.documentElement;
        const scrollable = doc.scrollHeight - window.innerHeight;
        const percent = scrollable <= 0 ? 100 : Math.round(((window.scrollY + window.innerHeight) / doc.scrollHeight) * 100);
        for (const threshold of SCROLL_THRESHOLDS) {
          if (percent >= threshold && !scrollFired.has(threshold)) {
            scrollFired.add(threshold);
            host.track("scroll_depth_reached", { percent: threshold });
          }
        }
      };
      if (scrollDepth) window.addEventListener("scroll", onScroll, { passive: true });

      onPage();

      return () => {
        history.pushState = originalPush;
        history.replaceState = originalReplace;
        window.removeEventListener("popstate", onPage);
        window.removeEventListener("scroll", onScroll);
        mutations?.disconnect();
        dwell?.destroy();
      };
    },
  };
}
