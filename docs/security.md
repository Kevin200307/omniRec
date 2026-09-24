# Security

## Central guarantee

Provider credentials never reach the browser. They are not obfuscated or
short-lived; they are never transmitted, because the browser has no code path to
a provider.

```
Browser  --(publishable key)-->  Event API  --(server-side credentials)-->  Provider
```

| Tier | Holds | Permitted operations |
| --- | --- | --- |
| Browser | `pk_live_...` and the endpoint URL | Submit events for one tenant |
| Merchant backend | An Event API key | Submit events for one tenant |
| Event API | AWS and Google credentials | Communicate with providers |

The guarantee is enforced structurally: `omnirec-commerce-core`,
`omnirec-event-api`, and both SDKs have no compile-time dependency on any
provider SDK, so a provider import in those modules does not compile.

### Verification

[`scripts/verify-bundle-security.mjs`](../scripts/verify-bundle-security.mjs)
scans the built frontend bundles for AWS access key identifiers, secret keys,
PEM blocks, service-account JSON documents, `sk_` keys, Azure connection
strings, and the provider SDKs themselves. It runs in continuous integration and
fails the build on any match.

The detection rules are deliberately narrow. An unqualified search for `AKIA`
matches the SDK's own guard expression, which exists to reject AWS keys, and an
unqualified search for `googleapis.com` matches the Next.js font preconnect
directive. False positives cause the check to be ignored, which is worse than
not performing it.

## The publishable key

The `pk_live_...` key is distributed in browser JavaScript and is visible to
anyone inspecting the page source. This is intended. The key permits exactly one
operation: submitting events for its own tenant. It grants no read access and
never unlocks provider credentials.

The key therefore provides routing and isolation rather than secrecy. Abuse is
bounded by rate limiting and payload limits rather than by concealment.

The SDK refuses to initialize if `apiKey` resembles an actual secret, such as a
value prefixed with `sk_`, `AKIA`, or `ASIA`, or a PEM header. This guards
against inadvertently supplying the wrong credential.

## Tenant isolation

The API key determines the tenant. A `tenantId` supplied in the request body is
advisory and ignored; trusting it would allow the holder of one tenant's
publishable key to write into another tenant's event stream. This is asserted by
`EndToEndPipelineTest.derivesTheTenantFromTheKeyRatherThanTheBody`.

Deduplication keys and identity links are namespaced per tenant, so one tenant's
identity graph can never resolve within another's.

Key comparison is constant-time across the entire key set. A publishable key is
not strictly a secret, so this is a defensive measure, but the same code is
likely to be reused for privileged keys, and a timing side channel that appears
only after a secret key is introduced is a poor way to discover the problem.

## Rejection of sensitive data

Card numbers, security codes, expiry dates, complete payment credentials,
passwords, and authentication secrets must never be collected.

This is enforced rather than merely documented. Both validators traverse the
entire event, at any depth and through objects and arrays, and reject it if a
field name matches a blocked name after normalization, which lowercases the name
and removes separators. Consequently `card_number`, `cardNumber`, and
`CardNumber` all match.

The blocked names are: `cardnumber`, `cardno`, `pan`, `cvv`, `cvc`, `cvv2`,
`securitycode`, `cardsecuritycode`, `expirymonth`, `expiryyear`, `cardexpiry`,
`password`, `passwd`, `pin`, `ssn`, `socialsecuritynumber`, `accesstoken`,
`refreshtoken`, `apikey`, `apisecret`, `secretkey`, `privatekey`,
`authorization`, `creditcard`, and `iban`.

The event is rejected rather than redacted. Silently removing the field would
leave the merchant believing the data had been accepted, and the call site would
never be corrected.

For `payment_information_added`, collect only non-sensitive metadata:

```js
commerce.checkout.paymentInformationAdded({ cartId: "cart_1", paymentMethod: "card" });
```

The two lists are kept identical by `CanonicalSchemaContractTest`. A field
blocked on one side but not the other is the dangerous case, because it appears
to be protected while a direct HTTP request bypasses the protection.

## Logging

Secrets, payment card data, API keys, and complete event payloads are never
logged. Logs record event identifiers, event types, tenant identifiers, and
field names from validation errors, but never field values.
`ValidationResult.describe()` is written for this purpose and is asserted not to
echo identity values.

Metrics are tagged only with bounded values, namely tenant, destination, and
reason. Using an event identifier or product identifier as a tag would create
one time series per event and overwhelm the metrics backend.

## Order of checks

Inexpensive checks run in a servlet filter before the request body is read:
payload size, then API key, then rate limit. An earlier version authenticated
within the controller, after Spring had already parsed the body, which allowed
any anonymous caller to force the server to parse a full-size request.
Rejections issued by the filter carry CORS headers for permitted origins;
without them the browser would report an opaque network error, which the SDK
must treat as retryable, and an invalid key would be retried indefinitely.

## Request controls

| Control | Default | Property |
| --- | --- | --- |
| API key authentication | required | `omnirec.events.tenants.<id>.api-key` |
| Rate limit | 300 requests per minute, per tenant per client address | `omnirec.events.rate-limit.*` |
| Maximum batch size | 500 events, then 413 | `omnirec.events.max-batch-size` |
| Maximum payload | 1 MB, then 413, whether declared or streamed | `omnirec.events.max-payload-bytes` |
| Disabled tenant | its key returns 401 | `omnirec.events.tenants.<id>.enabled` |
| Schema validation | enabled | always applied |
| IP retention | disabled | `omnirec.events.retain-ip-address` |

The rate limit is keyed on the connection address. It previously used the
left-most `X-Forwarded-For` value, which the client controls, so rotating a
forged header bypassed the limit (audit finding A5). Behind a load balancer, set
`server.forward-headers-strategy=native`, after which Tomcat resolves the
originating client address and trusts forwarded headers only from internal
proxies.

The rate limiter uses a per-instance fixed window, so across N replicas the
effective limit is N times the configured value, and a window boundary admits up
to twice the configured rate. It is a first line of defence against a single
misbehaving client rather than a quota system. Use a shared Redis counter where
accurate quotas are required.

Setting `allow-anonymous-ingestion` disables tenant isolation entirely, so
startup fails if it is enabled outside the `dev`, `test`, or `local` profiles.

## Network

- HTTPS is assumed throughout. The anonymous identifier cookie is marked
  `Secure` over HTTPS and `SameSite=Lax`.
- CORS permits only the configured storefront origins, only the `POST` method,
  and only the event endpoints. `allowCredentials` is disabled, because the key
  travels in a header and there is no reason to permit the browser to attach
  cookies.
- The `text/plain` content type is accepted on the batch endpoint solely because
  `navigator.sendBeacon` cannot set a content type and must remain a CORS simple
  request. The body remains JSON.
- `context.url` and `context.referrer` are sanitized to remove tokens, OAuth
  codes, email addresses, session identifiers, and URL credentials, both in the
  SDK and again in the API.
- The client IP address is used for coarse geolocation and rate limiting only.
  On an untrusted network path `X-Forwarded-For` can be forged, which is
  precisely why it is never used for identity.

## The legacy serving-side endpoint

The serving API (`omnirec-web`) predates the Event API and previously exposed
its own unauthenticated `POST /v1/events` endpoint. That endpoint trusted the
`tenantId` supplied in the body, omitted validation, deduplication, and
queueing, and called providers synchronously. It is now disabled by default
through `omnirec.web.legacy-ingestion.enabled` and logs a warning when enabled.
Its mappers have been aligned with the identity rules of the current adapters,
but it still lacks every other guarantee the Event API provides. Migrate to the
Event API rather than enabling it.

## Credentials in production

Provider credentials are resolved by each platform's own mechanism rather than
from configuration files:

- **AWS**: the default provider chain, using an IAM role or instance profile in
  production and `AWS_ACCESS_KEY_ID` with `AWS_SECRET_ACCESS_KEY` in local
  development.
- **Google**: Application Default Credentials, using workload identity in
  production and `GOOGLE_APPLICATION_CREDENTIALS` in local development.

Neither destination exposes an `accessKey` or `secretKey` property, so there is
no supported means of writing a long-lived secret into configuration and
committing it.

## Reporting a vulnerability

Report security issues privately to the maintainers rather than through a public
issue. See [SECURITY.md](../SECURITY.md) for the disclosure process.
