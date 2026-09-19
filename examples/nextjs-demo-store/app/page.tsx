"use client";

import { useEffect, useState } from "react";
import { useCommerce } from "@omnirec/commerce-react";
import { DEMO_PRODUCTS, DEMO_USER_ID } from "./products";

/**
 * Exercises the full pipeline from a real storefront:
 *
 *   click a product  -> product_viewed (+ dwellTimeMs when you move away)
 *   add to cart      -> product_added_to_cart
 *   log in           -> identify, linking this browser's anonymousId to a user
 *
 * Every event carries the anonymousId and sessionId automatically. Open the
 * console (debug is on) to watch them being built, and the Network tab to see
 * them leave in batches rather than one request per interaction.
 */
export default function HomePage() {
  const commerce = useCommerce();
  const [cartId] = useState(() => `cart_${Math.random().toString(36).slice(2, 8)}`);
  const [identity, setIdentity] = useState(() => commerce.getIdentity());
  const [log, setLog] = useState<string[]>([]);

  const record = (line: string) => setLog((entries) => [line, ...entries].slice(0, 12));

  // A page view per load, and the home-page event the discovery models use.
  useEffect(() => {
    commerce.page.viewed();
    commerce.home.viewed();
    record("page_viewed + home_page_viewed");
    // Impressions matter: without them a model cannot tell "not clicked" from
    // "never shown", which is the difference that makes clicks informative.
    commerce.productList.viewed({
      listId: "demo-grid",
      productIds: DEMO_PRODUCTS.map((product) => product.id),
    });
    record(`product_list_viewed (${DEMO_PRODUCTS.length} products)`);
  }, [commerce]);

  const refreshIdentity = () => setIdentity(commerce.getIdentity());

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 24 }}>
      <h1>Omnirec Commerce Tracking Demo</h1>
      <p style={{ color: "#666" }}>
        Events go to <code>{process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT ?? "http://localhost:8081"}</code> with a
        publishable key. Provider credentials live on the server and are never reachable from this page.
      </p>

      <section style={{ background: "#f6f6f6", borderRadius: 8, padding: 16, marginBottom: 24 }}>
        <h2 style={{ marginTop: 0 }}>Identity</h2>
        <dl style={{ display: "grid", gridTemplateColumns: "140px 1fr", gap: 4, margin: 0 }}>
          <dt>anonymousId</dt>
          <dd style={{ margin: 0 }}><code>{identity.anonymousId}</code></dd>
          <dt>sessionId</dt>
          <dd style={{ margin: 0 }}><code>{identity.sessionId}</code></dd>
          <dt>userId</dt>
          <dd style={{ margin: 0 }}><code>{identity.userId ?? "null (anonymous)"}</code></dd>
        </dl>
        <p style={{ color: "#666", fontSize: 14 }}>
          Reload the page: the anonymousId survives (a first-party cookie) and so does the session, until
          30 minutes of inactivity. Log in below and the anonymousId stays the same — the link between the
          two is recorded server-side rather than by rewriting history.
        </p>
        <button
          onClick={() => {
            commerce.user.loggedIn({ userId: DEMO_USER_ID });
            refreshIdentity();
            record(`user_logged_in + identify -> ${DEMO_USER_ID}`);
          }}
          disabled={identity.userId !== null}
        >
          Log in as {DEMO_USER_ID}
        </button>
        <button
          style={{ marginLeft: 8 }}
          onClick={() => {
            commerce.user.loggedOut();
            refreshIdentity();
            record("user_logged_out (anonymousId kept, session rotated)");
          }}
          disabled={identity.userId === null}
        >
          Log out
        </button>
      </section>

      <section style={{ marginBottom: 24 }}>
        <h2>Search</h2>
        <input
          type="search"
          placeholder="Try 'gaming laptop'…"
          style={{ padding: 8, width: 280 }}
          onKeyDown={(e) => {
            if (e.key !== "Enter") return;
            const query = (e.target as HTMLInputElement).value.trim();
            if (!query) return;
            commerce.search.performed({ query, resultCount: DEMO_PRODUCTS.length });
            record(`search_performed "${query}"`);
          }}
        />
      </section>

      <section style={{ marginBottom: 24 }}>
        <h2>Products</h2>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 16 }}>
          {DEMO_PRODUCTS.map((product) => (
            <div key={product.id} style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16 }}>
              <h3 style={{ margin: "0 0 8px", fontSize: 16 }}>{product.title}</h3>
              <p style={{ margin: "0 0 12px" }}>${product.price.toFixed(2)}</p>
              <button
                onClick={() => {
                  // Starts a dwell measurement too; the accumulated time arrives
                  // on a second product_viewed when you view something else.
                  commerce.product.viewed({
                    productId: product.id,
                    categoryId: product.categoryId,
                    price: product.price,
                    currency: "USD",
                  });
                  record(`product_viewed ${product.id}`);
                }}
              >
                View
              </button>
              <button
                style={{ marginLeft: 8 }}
                onClick={() => {
                  commerce.cart.productAdded({
                    cartId,
                    productId: product.id,
                    quantity: 1,
                    price: product.price,
                    currency: "USD",
                  });
                  record(`product_added_to_cart ${product.id}`);
                }}
              >
                Add to cart
              </button>
              <button
                style={{ marginLeft: 8 }}
                onClick={() => {
                  commerce.product.wishlisted({ productId: product.id });
                  record(`product_wishlisted ${product.id}`);
                }}
              >
                ♡
              </button>
            </div>
          ))}
        </div>
      </section>

      <section style={{ marginBottom: 24 }}>
        <h2>Checkout</h2>
        <button
          onClick={() => {
            commerce.checkout.started({ cartId });
            record("checkout_started");
          }}
        >
          Start checkout
        </button>
        <button
          style={{ marginLeft: 8 }}
          onClick={() => {
            // Only the method name. There is deliberately no argument on this
            // API that could carry a card number, and the validator would
            // reject the event if one appeared anyway.
            commerce.checkout.paymentInformationAdded({ cartId, paymentMethod: "card" });
            record("payment_information_added (method only — never card data)");
          }}
        >
          Add payment info
        </button>
        <p style={{ color: "#666", fontSize: 14 }}>
          Note there is no “complete purchase” button here. Purchases come from the backend SDK, where the
          payment result is actually known — a confirmation page can be reloaded, bookmarked, or never
          reached at all.
        </p>
      </section>

      <section>
        <h2>What just happened</h2>
        <button
          onClick={() => {
            void commerce.flush();
            record("flush() — forced an immediate send");
          }}
        >
          Flush now
        </button>
        <span style={{ marginLeft: 12, color: "#666" }}>{commerce.pending()} event(s) buffered</span>
        <ol style={{ fontFamily: "ui-monospace, monospace", fontSize: 13, color: "#333" }}>
          {log.map((entry, index) => (
            <li key={`${entry}-${index}`}>{entry}</li>
          ))}
        </ol>
      </section>
    </main>
  );
}
