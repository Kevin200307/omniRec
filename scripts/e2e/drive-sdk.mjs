// Drives the real, built @omnirec/commerce-web SDK through the spec's journey.
// A 1s session timeout stands in for "a day passes", so each "day" is a new session.
import { pathToFileURL } from "node:url";

const sdkPath = process.argv[2];
const { CommerceClient } = await import(pathToFileURL(sdkPath).href);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const errors = [];
const commerce = new CommerceClient({
  apiKey: "pk_test_demo_store",
  endpoint: "http://localhost:8124",
  sessionTimeoutMs: 1000,
  maxBatchSize: 100,
  onError: (e) => errors.push(e.message),
});

const journey = [];
const snap = (label) => journey.push({ label, ...commerce.getIdentity() });

// Day 1 — anonymous.
commerce.product.viewed({ productId: "p1", price: 1500, currency: "USD" });
await commerce.flush();
snap("day1");

// Day 2 — same device, new session, still anonymous.
await sleep(1200);
commerce.product.viewed({ productId: "p2", price: 99, currency: "USD" });
commerce.cart.productAdded({ cartId: "cart_1", productId: "p2", quantity: 2, price: 99, currency: "USD" });
await commerce.flush();
snap("day2");

// Day 3 — logs in, then keeps browsing.
await sleep(1200);
commerce.user.loggedIn({ userId: "customer_123" });
commerce.product.viewed({ productId: "p3", price: 49, currency: "USD" });
await commerce.flush();
snap("login");

// A card number must be refused client-side and never leave the browser.
commerce.checkout.paymentInformationAdded({ cartId: "cart_1", paymentMethod: "card", cardNumber: "4111111111111111" });
await commerce.flush();

console.log(JSON.stringify({ journey, errors, pending: commerce.pending() }, null, 2));
commerce.destroy();
process.exit(0);
