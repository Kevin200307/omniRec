# Tracking with HTML attributes

The `dom` plugin tracks events declared in markup, so most storefront tracking
needs no JavaScript. The `impressions` plugin does the same for things that are
seen rather than clicked, and `autocapture` covers what needs no declaration.

```js
import { createOmnirec } from "@omnirec/commerce-web";
import { dom } from "@omnirec/commerce-web/dom";
import { impressions } from "@omnirec/commerce-web/impressions";
import { autocapture } from "@omnirec/commerce-web/autocapture";

createOmnirec({ endpoint: "/omnirec", plugins: [dom(), impressions(), autocapture()] });
```

## Declaring an event

```html
<section data-omnirec-list="home_featured">
  <article data-omnirec-product="P100" data-omnirec-price="12.50" data-omnirec-currency="USD"
           data-omnirec-position="1" data-omnirec-impression="product_list_item_viewed">
    <a href="/p/P100" data-omnirec-event="product_clicked">Running shoe</a>
    <button data-omnirec-event="product_added_to_cart" data-omnirec-quantity="1">Add to cart</button>
  </article>
</section>
```

Clicking the button sends:

```json
{
  "event": "product_added_to_cart",
  "data": {
    "product": { "id": "P100", "price": "12.50", "currency": "USD", "quantity": 1 },
    "list": { "id": "home_featured", "position": 1 }
  }
}
```

- **Scope inheritance.** Fields are read from the element, then from each
  ancestor. The nearest value wins, so a card declares its product once and every
  button inside inherits it.
- **Delegation.** One listener per trigger on the document. Content rendered
  later by React, Vue or the server works without rebinding.
- **Triggers.** Forms fire on `submit`, form controls on `change`, everything
  else on `click`. Override with `data-omnirec-on="click|submit|change"`.

## Attribute reference

| Attribute | Goes to | Type |
|---|---|---|
| `data-omnirec-event` | event name | |
| `data-omnirec-on` | trigger | `click`, `submit`, `change` |
| `data-omnirec-impression` | event name sent when the element is seen | |
| `data-omnirec-product`, `-product-id` | `product.id` | string |
| `data-omnirec-product-name`, `-brand`, `-variant` | `product.name`, `product.brand`, `product.variantId` | string |
| `data-omnirec-price` | `product.price` | money, kept as written |
| `data-omnirec-currency` | `product.currency` | string |
| `data-omnirec-quantity` | `product.quantity` | integer |
| `data-omnirec-category`, `-category-name` | `category.id`, `category.name` | string |
| `data-omnirec-list`, `-list-name` | `list.id`, `list.name` | string |
| `data-omnirec-position` | `list.position` | integer |
| `data-omnirec-search`, `-search-query` | `search.query` | string |
| `data-omnirec-cart`, `-order` | `cart.id`, `order.id` | string |
| `data-omnirec-recommendation`, `-recommendation-provider` | `recommendation.id`, `recommendation.provider` | string |
| `data-omnirec-data` | merged into `data` | JSON object |
| `data-omnirec-props` | merged into `properties` | JSON object |
| any other `data-omnirec-foo-bar` | `data.fooBar`, for custom events | `true`/`false` and numbers converted |

Invalid JSON is reported through `onError`, never thrown. Events are still
validated against the catalog before sending, so a missing required field shows
up in the console during development.

## Impressions

`data-omnirec-impression="<event>"` sends the event when the element is at least
half visible for one second. Each element counts once per page view.
Options: `impressions({ threshold, minVisibleMs, sampleRate })`.

## Autocapture

`autocapture()` sends, with no markup:

- `page_viewed` on load and on every client-side navigation, and
  `home_page_viewed` on the home path;
- `scroll_depth_reached` at 25, 50, 75 and 90 percent, once each per page;
- `product_viewed` when the page declares `data-omnirec-page="product"` with
  product attributes, followed by a dwell-time update when the shopper leaves.

It also attaches UTM parameters and ad click ids (`gclid`, `fbclid`, `msclkid`,
`ttclid`) to every event in the session as `context.campaign`, and the declared
page type as `context.page`. It never guesses business events such as add to
cart from button text or CSS.

## Consent and debugging

```js
import { consent } from "@omnirec/commerce-web/consent";
import { debug } from "@omnirec/commerce-web/debug";

const cmp = consent();               // holds events until the visitor decides
createOmnirec({ endpoint: "/omnirec", plugins: [cmp, ...(dev ? [debug()] : [])] });
cmp.set({ analytics: true, marketing: false });
```

`debug()` logs every event, suggests the intended name for a typo
(`add_to_card` → `add_to_cart`), and warns when the same event for the same
product is sent twice within half a second, usually an attribute and a `track()`
call on the same click.
