# Identity

Omnirec uses three separate identifiers, governed by one rule: captured history
is never rewritten.

## The three identifiers

### `anonymousId`: the device

A UUID stored in a first-party cookie with a one-year lifetime, `SameSite=Lax`,
and the `Secure` attribute over HTTPS. It is created on the first visit.

The SDK never rotates this identifier and never clears it on sign-out. The
ability to recognize a returning visitor is the principal value of anonymous
behavioural data, because it allows a recommender to produce useful results
before any authentication has occurred.

The following are deliberately not used as device identity:

- **IP addresses**, which are shared by all clients behind network address
  translation, change on mobile network handover, and constitute personal data
  in most jurisdictions;
- **browser fingerprints**, which are unreliable, adversarial, and increasingly
  blocked by browsers.

### `sessionId`: the visit

A UUID stored in `localStorage` together with a last-activity timestamp. A new
session begins when any of the following holds:

1. no session exists;
2. the inactivity timeout has elapsed since the last tracked event (30 minutes
   by default, configurable through `sessionTimeoutMs`);
3. the application calls `logout()`.

`localStorage` is used rather than `sessionStorage` by design. `sessionStorage`
is scoped to a single tab and is cleared when that tab closes, which would both
fragment one visit across tabs and terminate a session when a visitor closes a
tab and returns shortly afterwards. The inactivity timeout defines session
boundaries; tab lifetime does not.

Sessions start automatically. The `session_started` event is emitted without an
explicit call by the application.

### `userId`: the customer

The merchant's own customer identifier, supplied through `identify()` or
`user.loggedIn()`. The SDK never generates one. It is stored in `localStorage`
so that it survives a page reload.

## Linking anonymous identity to a registered customer

When a visitor is identified, the SDK emits an `identify` event and the server
records a link:

```
identity_link
-------------
tenantId
anonymousId
userId
linkedAt
```

Previously captured events are not modified. An event captured anonymously
retains a null `userId` permanently.

Rewriting history would require re-reading and re-writing every event a visitor
had ever produced at the moment of authentication, which is unbounded work
triggered by a login, and it would destroy the record of what was genuinely
known at capture time. Storing the link separately makes attribution a join
operation instead.

The link resolves future events automatically. An event that arrives with an
`anonymousId` for which a link exists is enriched with the `userId` before it is
queued, so attribution remains correct even when a page omits the call to
`identify()`.

When [historical storage](event-storage.md) is enabled, the same links are also
persisted, per tenant, in the `identity_links` table alongside the stored
events, and `GET /v1/customers/{customerId}/events` uses them to return a
device's earlier anonymous events as part of the customer's journey. The stored
anonymous rows are not modified; they keep `user_id = NULL`. To reach storage,
the `identify` event is routed as a control event to the storage worker only. It
still never reaches a provider.

### Example journey

```
Day 1   anonymousId = anon_A   sessionId = session_1   userId = null
Day 2   anonymousId = anon_A   sessionId = session_2   userId = null
Login   anonymousId = anon_A   sessionId = session_3   userId = customer_123
                                    |
                                    v
                        identity_link: anon_A -> customer_123
```

The events from day 1 and day 2 continue to record `userId: null`. The link
makes them attributable to `customer_123`, and all subsequent events carry the
`userId` directly.

This behaviour is verified by
`EndToEndPipelineTest.linksDaysOfAnonymousBrowsingToTheUserWhoEventuallyLogsIn`.

### Multiple devices for one customer

The relationship is many-to-one by design:

```
anon_A (laptop) -> customer_123
anon_B (phone)  -> customer_123
```

`IdentityLinkStore.anonymousIdsFor(tenant, userId)` returns every device known
for a customer, which is how history is assembled across devices.

### Shared devices

When one `anonymousId` is linked to two customers, whether through a shared
device or an account change, the most recent link takes precedence, because it
reflects the current user of the device.

## Changing user without signing out

If a different customer is identified while another is signed in, the session
rotates exactly as it would on sign-out, so that a single session never contains
the behaviour of two people. An anonymous visitor who authenticates retains the
current session, because that continuity is what allows providers to associate
pre-authentication browsing with the customer.

## Sign-out

```
userId        -> cleared
anonymousId   -> unchanged
sessionId     -> rotated
identity_link -> retained
```

Two of these behaviours warrant explanation:

- **The session rotates**, so that no session contains events produced by two
  different people.
- **The link is retained.** Deleting it would discard the knowledge that the
  device belongs to that customer, which is precisely what makes a returning
  visitor's pre-authentication experience effective. Erasing a person requires
  deleting their links and events; sign-out is not a deletion request.

## Server-side identity

A server-side event generally has the `userId` available but not the browser's
`anonymousId`.

Supply the `anonymousId` where it is available. Capture it at checkout and store
it with the order, as it is what links a purchase to the browsing that preceded
it.

When it is not supplied, the SDK derives a stable `server:<uuid-of-userId>`
anonymous identifier. Server-side events then remain coherent with one another,
but they are not joined to the browser identity, which is why supplying the
actual value is preferable.

## Privacy

- The client IP address is used for coarse geolocation and rate limiting, then
  discarded unless `omnirec.events.retain-ip-address=true`.
- Identity is held in first-party storage only. No identifier is shared across
  sites.
- The identity link store is the only component that retains identity data long
  term. It is defined as an interface, so a merchant may implement it against
  their own database.
