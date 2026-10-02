# @omnirec/commerce-web

Browser SDK for OmniRec commerce event tracking.

```js
import { createOmnirec } from "@omnirec/commerce-web";
import { dom } from "@omnirec/commerce-web/dom";
import { autocapture } from "@omnirec/commerce-web/autocapture";

const omnirec = createOmnirec({ endpoint: "/omnirec", plugins: [dom(), autocapture()] });
omnirec.track("product_added_to_cart", { product: { id: "P100", quantity: 1 } });
```

Plugins: `dom`, `impressions`, `autocapture`, `consent`, `debug`. Script tag:
`dist/omnirec.min.js`. See [docs/frontend-sdk.md](../../docs/frontend-sdk.md) and
[docs/html-attributes.md](../../docs/html-attributes.md).
