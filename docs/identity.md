# Identity

Three identities, deliberately separate, with one rule connecting them: **history
is never rewritten**.

## The three

### `anonymousId` — the device

A UUID in a first-party cookie, one year, `SameSite=Lax`, `Secure` over HTTPS.
Created on first visit.

It is **never rotated by the SDK and never cleared on logout**. A returning
visitor staying recognisable is the entire value of anonymous behavioural data —
it's what lets a recommender say something useful before anyone logs in.

Deliberately *not*:

- **an IP address** — shared by everyone behind a NAT, changes on every mobile
  handover, and is personal data in most jurisdictions;
- **a browser fingerprint** — fragile, hostile, and increasingly blocked.

### `sessionId` — the visit

A UUID in `localStorage` with a last-activity timestamp. A new session starts
when:

1. no session exists, **or**
2. more than the inactivity timeout (default 30 minutes, configurable via
   `sessionTimeoutMs`) has passed since the last tracked event, **or**
3. the merchant calls `logout()`.

`localStorage`, not `sessionStorage`, is deliberate. `sessionStorage` is per-tab
and dies on tab close, which would both fragment one visit across tabs and end a
session the moment someone closes a tab to come back two minutes later. **The
idle timeout is the policy; tab lifetime is not.**

Sessions start automatically — `session_started` is emitted without the merchant
calling anything.

### `userId` — the customer

The merchant's own id, supplied through `identify()` or `user.loggedIn()`. **The
SDK never invents one.** Stored in `localStorage` so it survives a reload.

## Linking anonymous to registered

When a visitor identifies, the SDK emits an `identify` event and the server
records a link:

```
identity_link
-------------
tenantId
anonymousId
userId
linkedAt
```

**Past events are not rewritten.** An event captured anonymously keeps its null
`userId` forever.

Why: rewriting would mean re-reading and re-writing every event a visitor ever
produced the instant they log in — unbounded work triggered by a login — and it
would destroy the record of what was genuinely known at capture time. Keeping the
link separate makes attribution a join instead.

What the link does do is let *future* anonymous events be resolved automatically.
An event arriving with an `anonymousId` that has a known link is enriched with
the `userId` before it reaches the queue, so a merchant who forgets to call
`identify()` on one page still gets correct attribution.

### The journey

```
Day 1   anonymousId = anon_A   sessionId = session_1   userId = null
Day 2   anonymousId = anon_A   sessionId = session_2   userId = null
Login   anonymousId = anon_A   sessionId = session_3   userId = customer_123
                                    |
                                    v
                        identity_link: anon_A -> customer_123
```

Day 1 and Day 2's events still say `userId: null`. The link makes them
attributable to `customer_123`, and everything after it carries the `userId`
directly.

Covered end to end by `EndToEndPipelineTest.linksDaysOfAnonymousBrowsingToTheUserWhoEventuallyLogsIn`.

### Several devices, one shopper

Many-to-one, by design:

```
anon_A (laptop) -> customer_123
anon_B (phone)  -> customer_123
```

`IdentityLinkStore.anonymousIdsFor(tenant, userId)` returns every device known
for a customer, which is how history is gathered across them.

### A shared device

If one `anonymousId` links to two users — a shared family laptop, or a genuine
account switch — **the most recent link wins**, because that reflects who is
actually using the device now.

## Switching users without logging out

If a different user identifies while another is signed in (a shared device, an
account switch), the session rotates, exactly as a logout would give it. One
session never contains two people's behaviour. An anonymous visitor logging in
keeps the session: that continuity is what lets providers stitch pre-login
browsing to the user.

## Logout

```
userId    -> null        (cleared)
anonymousId -> unchanged (kept)
sessionId -> rotated     (new session)
identity_link -> kept    (not deleted)
```

Two of these deserve justification:

- **The session rotates** so no single session contains events from two different
  people.
- **The link survives.** Deleting it would throw away the knowledge that this
  device belongs to that shopper — exactly what makes a returning visitor's
  pre-login experience good. To genuinely forget a person, delete their links
  and events; logout is not a deletion request.

## Server-side identity

A backend event usually knows the `userId` but not the browser's `anonymousId`.

**Pass the `anonymousId` when you have it** — capture it at checkout and store it
against the order. It is what links a purchase to the browsing that led to it.

When you don't, the SDK derives a stable `server:<uuid-of-userId>` anonymousId.
Their server-side events stay coherent with one another, but this does *not*
magically join them to the browser identity — which is why passing the real one
is worth the trouble.

## Privacy notes

- The IP is used for coarse geo and rate limiting, then discarded unless
  `omnirec.events.retain-ip-address=true`.
- Identity lives in first-party storage only. Nothing is shared cross-site.
- The identity link store is the one component holding identity data long-term;
  it is an interface, so a merchant can keep it in their own database.
