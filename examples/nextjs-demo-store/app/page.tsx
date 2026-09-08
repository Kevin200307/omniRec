"use client";

import { useOmnirec } from "@omnirec/react";
import { ProductImpression, SearchBar, RecommendationCarousel, RecentlyViewedCarousel } from "@omnirec/react-ui";
import { DEMO_PRODUCTS, DEMO_USER_ID } from "./products";

/**
 * The entire product page's tracking surface: <SearchBar>, <ProductImpression>
 * (auto click + dwell time), and useOmnirec().trackCartAdd for the one
 * signal that has no generic DOM hook. Scroll depth and cart abandonment
 * need no code here at all — they're plugin-driven from providers.tsx.
 */
export default function HomePage() {
  const { trackCartAdd, track } = useOmnirec();

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 24 }}>
      <h1>Omnirec Demo Store</h1>
      <p style={{ color: "#666" }}>
        Every interaction below is captured by @omnirec/core and sent to the backend at{" "}
        <code>{process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT ?? "http://localhost:8080"}</code>.
      </p>

      <section style={{ marginBottom: 32 }}>
        <h2>Search</h2>
        <SearchBar renderHit={(hit) => <span>{String(hit.title ?? hit.id)}</span>} />
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>Products</h2>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 16 }}>
          {DEMO_PRODUCTS.map((product) => (
            <ProductImpression
              key={product.id}
              productId={product.id}
              className="product-card"
              onClick={() => track("PRODUCT_VIEWED", "IMPLICIT", { productId: product.id })}
            >
              <div style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16 }}>
                <h3 style={{ margin: "0 0 8px" }}>{product.title}</h3>
                <p style={{ margin: "0 0 12px" }}>${product.price.toFixed(2)}</p>
                <button
                  onClick={() => {
                    trackCartAdd(product.id);
                  }}
                >
                  Add to cart
                </button>
                <button
                  style={{ marginLeft: 8 }}
                  onClick={() => track("WISHLIST_ADD", "EXPLICIT", { productId: product.id })}
                >
                  ♡ Wishlist
                </button>
              </div>
            </ProductImpression>
          ))}
        </div>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>Recommended for you</h2>
        <RecommendationCarousel
          userId={DEMO_USER_ID}
          fallback={<p style={{ color: "#999" }}>No recommendations yet — click a few products above first.</p>}
          renderItem={(item) => (
            <div key={item.productId} style={{ border: "1px solid #eee", borderRadius: 8, padding: 12, display: "inline-block", marginRight: 12 }}>
              {item.productId} (score: {item.score?.toFixed?.(1) ?? item.score})
            </div>
          )}
        />
      </section>

      <section>
        <h2>Recently viewed</h2>
        <RecentlyViewedCarousel
          userId={DEMO_USER_ID}
          renderItem={(item) => (
            <div key={item.productId} style={{ border: "1px solid #eee", borderRadius: 8, padding: 12, display: "inline-block", marginRight: 12 }}>
              {item.productId}
            </div>
          )}
        />
      </section>
    </main>
  );
}
