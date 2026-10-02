# The event catalog

`catalog/` defines every standard event once, in YAML. Everything else is
generated from it by `npm run catalog:generate`, and CI runs
`npm run catalog:check` to fail on stale output:

- TypeScript types and runtime rules for the browser SDK
- Java `StandardEvents` / `StandardEventNames` and the JVM registry
- the JSON Schema (`schema/commerce-event.schema.json`)
- the CLI's copy of the catalog
- the reference in [docs/events/](events/README.md)

## Shape

```
catalog/
  catalog.yaml            version and the ordered list of domains
  blocks/*.yaml           reusable groups of fields: product, cart, order, shipment, ...
  vocabularies/*.yaml     allowed values: payment_method, return_reason, ...
  events/<domain>/*.yaml  one file per event
  envelope/v2.schema.json the envelope around every event
```

An event:

```yaml
name: product_added_to_cart
domain: cart_checkout
version: 1
sources: [browser, server]
description: "A product was added to the cart."
aliases: [add_to_cart]
blocks: [product, cart]
properties:
  product.id: { required: true }
  product.quantity: { required: true, minimum: 1 }
example:
  product: { id: p123, quantity: 2 }
  cart: { id: c1 }
```

On the wire, the event's fields live under `data`, grouped by block:

```json
{ "event": "product_added_to_cart", "data": { "product": { "id": "p123", "quantity": 2 }, "cart": { "id": "c1" } } }
```

## Domains

| Domain | Examples |
| --- | --- |
| session_engagement | `page_viewed`, `session_started`, `scroll_depth_reached`, `return_visit` |
| acquisition_messaging | `ad_clicked`, `message_opened`, `referral_link_clicked` |
| search_discovery | `search_performed`, `search_filter_applied`, `product_list_viewed` |
| product_page | `product_viewed`, `variant_selected`, `product_wishlisted` |
| cart_checkout | `product_added_to_cart`, `checkout_started`, `cart_abandoned` |
| orders_payments | `purchase_completed`, `payment_failed`, `repeat_purchase` |
| fulfilment | `shipment_shipped`, `delivery_completed`, `delivery_failed` |
| support | support conversations, tickets and chatbot sessions |
| returns_refunds | `return_initiated`, `refund_issued`, `chargeback_opened` |
| reviews_advocacy | `product_review_submitted`, `referral_shared` |
| account_retention | sign-ups, subscriptions, loyalty |
| identity | `identify` |

The generated [event reference](events/README.md) lists all 205 events with
their fields.

## Names and aliases

Canonical names stay stable: an event is never renamed, because stored data
and every sender depend on the name. Shorter or alternative names are
**aliases**, for example `add_to_cart` for `product_added_to_cart`. The SDKs
type them, and the collector rewrites them to the canonical name, so
destinations and storage see one name only.

## Sources

`sources` says where an event is expected to come from:

| Source | Meaning |
| --- | --- |
| `browser` | The SDKs |
| `server` | Your backend: authoritative facts such as purchases |
| `webhook` | Another system, through `/v1/webhooks/{source}` |
| `derived` | Computed by omniRec, for example `cart_abandoned` |
| `import` | Backfills |

## Changing the catalog

1. Edit or add YAML under `catalog/`, with a description and an `example`.
2. Run `npm run catalog:generate` and commit the generated files.
3. Every destination's coverage test then requires the new event to be mapped
   or explicitly ignored.
4. Both languages' validators run every example, and fail if removing a
   required field does not fail validation.

For your own store's events, do not edit the catalog. Use a tracking plan
([custom-events.md](custom-events.md)).
