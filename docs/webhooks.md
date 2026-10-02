# Webhooks

omniRec handles webhooks in both directions:

- **Inbound**: other systems (Stripe, a shipping provider, your ERP) post to
  `POST /v1/webhooks/{source}`, and their events join the same pipeline as
  browser and server events.
- **Outbound**: the webhook destination posts omniRec events to your own URLs,
  signed, with retries.

## Inbound webhooks

A webhook needs no API key. The sender's signature is the credential. Each
source has a secret, and a call whose signature does not verify gets `401`
before its body is read as JSON.

| Answer | When |
| --- | --- |
| `202` | Accepted. The body is the usual ingest response (`accepted`, `duplicates`, `rejected`, `errors`). |
| `202` with `rejected` > 0 | The mapping produced an invalid event. It is logged as a warning. The sender is not asked to retry, because a retry would send the same body again. |
| `400` | The body is not what the source expects. |
| `401` | The signature is missing or invalid, or the tenant has no secret for this source. |
| `404` | No source with that name is configured. |
| `413` | The body exceeds `omnirec.events.max-payload-bytes`. |
| `503` | The queue is unavailable. Senders retry. |

Translated events are normal v2 events with `source: webhook`. They are
normalized, validated against the tenant's catalog and plan, deduplicated and
delivered like any other event. Each event id is derived from the sender's own
id, so a sender's retry is recognised as a duplicate.

### Tenants

A call writes to the default tenant (`omnirec.events.default-tenant-id`), or to
the tenant named by `?tenant=<id>`. The tenant must have its own secret
(`tenant-secrets.<id>`). The default `secret` only opens the default tenant, so
a sender that holds one store's secret can never write to another store.

### Stripe

```yaml
omnirec:
  webhooks:
    stripe:
      enabled: true
      secret: ${STRIPE_WEBHOOK_SECRET}     # whsec_..., from the Stripe dashboard
```

Point a Stripe webhook endpoint at `https://collector.example.com/v1/webhooks/stripe`
and subscribe it to these events:

| Stripe event | omniRec event |
| --- | --- |
| `charge.dispute.created` | `chargeback_opened` |
| `charge.dispute.closed` (status `won`, `warning_closed` or `lost`) | `chargeback_resolved` with `outcome` |
| `charge.refunded` | `refund_issued` |

Other Stripe event types are acknowledged and ignored.

- **Order**: `metadata.order_id` (or `orderId`) on the charge or dispute.
  Without it, the payment intent id, then the charge id. Set this metadata when
  you create the payment so refunds and disputes join the order.
- **Customer**: `metadata.user_id` becomes the event's `userId`. The Stripe
  customer id becomes the anonymous id `stripe_<customer>`.
- **Amounts**: Stripe's minor units are converted using the currency's
  decimals (cents for USD, none for JPY, three for KWD).
- **Signatures**: Stripe's scheme (`Stripe-Signature: t=...,v1=...`). Signatures
  older than `tolerance` (5 minutes) are refused as replays. During a secret
  rollover Stripe sends several `v1` values, and any match is accepted.

### Generic JSON sources

Most tools that sign a JSON body with HMAC-SHA256 can be connected with
configuration alone:

```yaml
omnirec:
  webhooks:
    json:
      shipping:                               # served at /v1/webhooks/shipping
        secret: ${SHIPPING_WEBHOOK_SECRET}
        signature-header: X-Signature          # default
        signature-prefix: "sha256="            # removed when present (default)
        signature-encoding: hex                # or base64 (Shopify)
        events-path: events                    # optional: where an array of events is
        id-path: id                            # the sender's event id (default "id")
        type-path: type                        # matched against "when" (default "type")
        timestamp-path: occurred_at            # ISO-8601 or epoch seconds/millis
        events:
          - when: shipment.delivered
            event: delivery_completed
            user-id: customer.ref              # the store's user id, when the sender knows it
            subject: customer.id               # the sender's customer id
            data:
              shipment:
                id: shipment.id
                carrier: shipment.carrier
                method: =express               # "=" makes a constant
            properties:
              attempts: shipment.attempts
          - when: order.paid
            event: purchase_completed
            data:
              order:
                id: order.ref
                total: order.total
                currency: =USD
                items: { each: lines, productId: sku, quantity: qty, price: unit_price }
```

- Paths are dot-separated, with array indexes: `order.lines[0].sku`.
- A missing value leaves the field out, and the validator then names the
  required field that is missing.
- `each` maps an array element by element, with paths relative to each element.
- Every mapping whose `when` matches produces one event. A mapping without
  `when` matches every event.
- Startup fails on a source without a secret, without mappings, or with a
  mapping whose `event` is not a valid name.

### Your own adapter

For a sender that needs code, implement `WebhookAdapter` and register it as a
bean:

```java
@Component
class ShopifyOrdersAdapter implements WebhookAdapter {
    public String source() { return "shopify"; }
    public boolean verify(WebhookRequest request) { /* constant-time HMAC check */ }
    public List<ObjectNode> translate(WebhookRequest request) {
        // WebhookEnvelope.create(mapper, "shopify", "shopify:" + id, "purchase_completed", createdAt)
        //     .userId(...).subject(...); fill envelope.block("order"); return List.of(envelope.build());
    }
}
```

`WebhookSignatures` has the HMAC and constant-time comparison helpers.

## Outbound webhooks

```yaml
omnirec:
  destinations:
    webhook:
      enabled: true
      endpoints:
        crm:
          url: https://crm.example.com/hooks/omnirec
          secret: ${CRM_WEBHOOK_SECRET}
          events: [purchase_completed, cart_abandoned, "refund_*"]
          tenants: [store-a]              # optional
          headers:                        # optional
            Authorization: Bearer ${CRM_TOKEN}
```

Each endpoint is its own destination (`webhook-crm`), with its own queue, retry
tiers and dead-letter queue, so a slow receiver never delays another. `events`
takes names, `prefix_*`, or `*`. Events reach this destination with canonical
names, so list canonical names, not aliases.

### Requests

```http
POST /hooks/omnirec HTTP/1.1
Content-Type: application/json
X-Omnirec-Signature: t=1790000000,v1=<64 hex characters>
X-Omnirec-Delivery: 3f0c9a4e-...

{"events":[{"eventId":"...","event":"purchase_completed","data":{...}, ...}]}
```

Verify the request the way Stripe's is verified:

1. Split the header into `t` and `v1`.
2. Compute HMAC-SHA256 of `<t>.<raw body>` with the secret, as lowercase hex.
3. Compare it with `v1` in constant time.
4. Reject the request if `t` is more than a few minutes old.

Delivery is at least once, so deduplicate by `eventId`.

### Responses

| Receiver answers | omniRec does |
| --- | --- |
| `2xx` | Done |
| `408`, `429`, `5xx`, or no answer | Retries through the retry tiers, then the dead-letter queue |
| Any other `4xx` | Dead-letters the event at once, because the receiver will never accept it |

The URL must use HTTPS, except `localhost`, because events contain customer
data.
