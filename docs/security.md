# Security

## The central claim

**Provider credentials never reach the browser.** Not obfuscated, not
short-lived — they are never sent, because the browser has no code path to a
provider at all.

```
Browser  ──(publishable key)──►  Event API  ──(server-side credentials)──►  Provider
```

| Tier | Holds | Can do |
|---|---|---|
| Browser | `pk_live_…` + endpoint URL | Write events for one tenant |
| Merchant backend | An Event API key | Write events for one tenant |
| **Event API** | AWS / Google credentials | Talk to providers |

Enforced structurally: `omnirec-commerce-core`, `omnirec-event-api`, and both
SDKs have **no compile-time dependency** on a provider SDK. A provider import
there does not compile.

### Verified, not asserted

[`scripts/verify-bundle-security.mjs`](../scripts/verify-bundle-security.mjs)
scans the built frontend bundles for AWS key ids, secret keys, PEM blocks,
service-account JSON, `sk_` keys, Azure connection strings, and the provider SDKs
themselves. It runs in CI and fails the build on a hit.

The rules are narrow on purpose. A bare search for `AKIA` matches the SDK's own
guard regex — the code whose job is to *reject* an AWS key — and a bare search
for `googleapis.com` matches Next.js's font preconnect. False positives train
everyone to ignore the script, which is worse than not having it.

## The publishable key

`pk_live_…` ships in browser JavaScript and is visible to anyone who views
source. **That is by design.** It can do exactly one thing: write events for its
own tenant. It cannot read anything, and it never unlocks provider credentials.

So this key is about **routing and isolation**, not secrecy. Abuse is bounded by
rate limiting and payload caps, not by hiding it.

The SDK refuses to start if `apiKey` looks like a real secret (`sk_`, `AKIA…`,
`ASIA…`, a PEM header) — a guardrail against pasting the wrong credential in.

## Tenant isolation

The **API key decides the tenant**. A `tenantId` in the request body is advisory
and ignored; trusting it would let anyone holding one tenant's publishable key
write into another's stream. Asserted by
`EndToEndPipelineTest.derivesTheTenantFromTheKeyRatherThanTheBody`.

Deduplication keys and identity links are namespaced per tenant, so one tenant's
identity graph can never resolve in another's.

Key comparison is constant-time across the whole key set. A publishable key isn't
really a secret, so this is belt-and-braces — but the same code shape gets reused
for privileged keys, and a timing oracle that only appears once someone adds a
secret key is a nasty way to find out.

## Sensitive data is refused outright

Never collect card numbers, CVVs, expiry dates, full payment credentials,
passwords, or authentication secrets.

This is enforced, not just documented. Both validators walk the whole event — at
any depth, through objects and arrays — and **reject** it if a field name matches
a blocked name after normalisation (lowercased, separators stripped), so
`card_number`, `cardNumber`, and `CardNumber` all match.

Blocked: `cardnumber`, `cardno`, `pan`, `cvv`, `cvc`, `cvv2`, `securitycode`,
`cardsecuritycode`, `expirymonth`, `expiryyear`, `cardexpiry`, `password`,
`passwd`, `pin`, `ssn`, `socialsecuritynumber`, `accesstoken`, `refreshtoken`,
`apikey`, `apisecret`, `secretkey`, `privatekey`, `authorization`, `creditcard`,
`iban`.

**Rejection, not redaction.** Quietly stripping the field would leave the merchant
believing the data was accepted, and they'd never fix the call site.

For `payment_information_added`, collect only safe metadata:

```js
commerce.checkout.paymentInformationAdded({ cartId: "cart_1", paymentMethod: "card" });
```

The two lists are kept identical by `CanonicalSchemaContractTest` — a field
blocked on one side but not the other is the dangerous case, because it reads as
protected while a direct POST sails past.

## Never logged

Secrets, card data, API keys, and full event payloads. Logs carry event ids,
event types, tenant ids, and field *names* from validation errors — never field
values. `ValidationResult.describe()` is built for exactly this and is asserted
not to echo identity values.

Metrics are tagged only with bounded values (tenant, destination, reason). An
event id or product id as a tag would create a time series per event and take the
metrics backend down.

## Order of checks

The cheap checks run in a servlet filter **before the body is read**: payload
size, then API key, then rate limit. An earlier version authenticated inside the
controller, after Spring had already parsed the body, so any anonymous caller
could make the server parse a full-size request. Rejections from the filter carry
CORS headers for allowed origins; without them, the browser would report an
opaque network error, which the SDK must treat as retryable, and a bad key would
be retried forever.

## Request controls

| Control | Default | Property |
|---|---|---|
| API key authentication | required | `omnirec.events.tenants.<id>.api-key` |
| Rate limit | 300 req/min per tenant per IP | `omnirec.events.rate-limit.*` |
| Max batch size | 500 events → 413 | `omnirec.events.max-batch-size` |
| Max payload | 1 MB → 413 (declared or streamed) | `omnirec.events.max-payload-bytes` |
| Disabled tenant | its key → 401 | `omnirec.events.tenants.<id>.enabled` |
| Schema validation | on | always |
| IP retention | **off** | `omnirec.events.retain-ip-address` |

The rate limit is keyed on the connection's address. It used to key on the
left-most `X-Forwarded-For`, which the client writes, so rotating a fake header
bypassed it (audit finding A5). Behind a load balancer, set
`server.forward-headers-strategy=native`; Tomcat then resolves the real client
address and trusts forwarded headers only from internal proxies.

The rate limiter is per-instance fixed-window, so behind N replicas the effective
limit is N × the configured value, and a window boundary admits up to 2×. It is a
first line of defence against one misbehaving client, not a quota system; move it
to a shared Redis counter if you need a real one.

`allow-anonymous-ingestion` disables tenant isolation entirely, so **startup
fails** if it is set outside the `dev`, `test`, or `local` profiles.

## Network

- HTTPS assumed everywhere; the anonymous-id cookie is `Secure` over HTTPS and
  `SameSite=Lax`.
- CORS allows only configured storefront origins, only `POST`, only on the event
  endpoints. `allowCredentials` is off — the key travels in a header, so there is
  no reason to let the browser attach cookies.
- `text/plain` is accepted on the batch endpoint solely because
  `navigator.sendBeacon` cannot set a Content-Type and must stay a CORS simple
  request. The body is still JSON.
- `context.url` and `context.referrer` are scrubbed of tokens, OAuth codes,
  emails, session ids and URL credentials, in the SDK and again in the API.
- The client IP is used for coarse geo and rate limiting only. Behind an
  untrusted path `X-Forwarded-For` is spoofable, which is exactly why it is never
  used for identity.

## The legacy serving-side endpoint

The serving API (`omnirec-web`) predates the Event API and used to expose its own
**unauthenticated** `POST /v1/events`. It trusted the body's `tenantId`, skipped
validation, deduplication and the queue, and called providers synchronously. It
is now **off by default** (`omnirec.web.legacy-ingestion.enabled`), and logs a
warning if turned on. Its mappers were brought in line with the new adapters'
identity rules, but it still lacks everything else the Event API guarantees.
Migrate to the Event API rather than enabling it.

## Credentials in production

Provider credentials are resolved by each cloud's own mechanism, not from
configuration files:

- **AWS** — the default provider chain: an IAM role or instance profile in
  production, `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` locally.
- **Google** — Application Default Credentials: workload identity in production,
  `GOOGLE_APPLICATION_CREDENTIALS` locally.

Neither destination has an `accessKey`/`secretKey` property, so there is no
supported way to write a long-lived secret into config and have it committed.

## Reporting

Security issues should go to the maintainers privately rather than through a
public issue.
