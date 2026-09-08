# Omnirec — Implementation Plan

Goal: ship a working vertical slice fast, prove the abstraction holds by adding a second provider without touching the facade, then broaden. Optimized for solo/small-team execution speed — every phase below produces something runnable, not just scaffolding.

---

## 0. Execution principles (apply throughout, not just phase 0)

1. **Code to the interface before the integration.** `RecommendationProvider` / `SearchProvider` / `CacheProvider` get written — and a trivial `InMemory*` fake implementation shipped — *before* any AWS/Algolia account exists. This unblocks frontend + facade work immediately instead of waiting on Personalize campaign training (which takes hours) or Algolia signup.
2. **One full vertical slice before breadth.** Phase 1 ships Personalize + Algolia *completely* (frontend → backend → provider → back to UI) before Google Rec AI or Redis are touched. A shallow stub of all four providers is worse than one that fully works.
3. **Sync before async.** The Kafka/SQS event pipeline described in the design doc is a Phase 2 concern, not Phase 1. Ship a synchronous dispatch first — it's simpler to debug and the queue is a drop-in swap later because the facade already isolates callers from it.
4. **The second provider is the real test of the abstraction.** If adding Google Rec AI in Phase 2 requires changing `PersonalizationService`, `IngestionController`, or any frontend component, that's a design bug to fix immediately — not a one-off exception.
5. **Path-filtered CI from day one.** JS-only changes shouldn't wait on Maven, and vice versa. Set this up in Phase 0 so the monorepo doesn't start feeling slow to work in.

---

## 1. Repo layout

```
omniRec/
├── packages/                      # JS/TS — pnpm workspaces + Turborepo
│   ├── core/                      # @omnirec/core — tracker, queue, identity, plugin system
│   ├── react/                     # @omnirec/react — OmnirecProvider, useOmnirec
│   └── react-ui/                  # @omnirec/react-ui — ProductImpression, SearchBar, carousels
├── backend/                       # Java — Maven multi-module reactor
│   ├── omnirec-core/              # canonical DTOs, provider interfaces, PersonalizationService
│   ├── omnirec-web/                # IngestionController, enrichment middleware, REST layer
│   ├── omnirec-personalize-starter/
│   ├── omnirec-algolia-starter/
│   ├── omnirec-google-recai-starter/
│   └── omnirec-redis-starter/
├── schema/                        # canonical event JSON Schema — single source of truth
├── examples/
│   └── nextjs-demo-store/         # proof-of-DX: clone → install → docker-compose up → works
├── .github/workflows/
│   ├── js.yml                     # triggers on packages/**
│   └── java.yml                   # triggers on backend/**
└── docs/
```

`schema/canonical-event.schema.json` is generated into both `packages/core/src/types.ts` and `backend/omnirec-core/.../EventDto.java`. Treat schema drift between the two as a CI-failing bug, not a style nit — this is the seam every provider mapper depends on.

---

## 2. Phase-by-phase plan

### Phase 0 — Foundations (days 1–3)
- [ ] pnpm workspace + Turborepo config; `tsup` for fast package builds (avoid webpack for library builds — too slow for this iteration loop)
- [ ] Maven parent POM + reactor build for `backend/`
- [ ] Canonical event schema v1 frozen in `schema/`
- [ ] Core interfaces written, with in-memory fakes:
  ```java
  public interface RecommendationProvider {
      void putEvents(List<CanonicalEvent> events);
      List<Recommendation> getRecommendations(String userId, RecContext ctx);
  }
  public interface SearchProvider {
      SearchResult search(String query, Map<String,Object> filters);
      void indexItems(List<Item> items);
  }
  public interface CacheProvider {
      void set(String key, Object value, Duration ttl);
      Optional<Object> get(String key);
  }
  ```
- [ ] `PersonalizationService` facade — dispatches to whichever providers are registered as Spring beans, no provider-specific logic inside it
- [ ] GitHub Actions: `js.yml` (path filter `packages/**`), `java.yml` (path filter `backend/**`)

**Exit criteria:** `mvn -pl backend -am install` and `pnpm -w build` both pass on an empty-but-wired skeleton.

### Phase 1 — MVP vertical slice: Personalize + Algolia (weeks 1–3)
Frontend:
- [ ] `@omnirec/core`: anonymous ID (cookie-based) + identity merge on login, event queue (batched, interval + size-triggered flush), transport with `sendBeacon` fallback for unload events
- [ ] Plugins: `dwellTime` (IntersectionObserver + Page Visibility API), `cart` (inactivity timer + beacon-on-unload for abandonment), `scrollDepth` (25/50/75/100%)
- [ ] `@omnirec/react`: `OmnirecProvider`, `useOmnirec()`
- [ ] `@omnirec/react-ui`: `<ProductImpression>` (auto click + dwell), `<SearchBar>` (calls backend `/v1/search`, provider-agnostic from the component's POV)

Backend:
- [ ] `IngestionController` (`POST /v1/events`) — enrich server-side: geo-IP, UA-parsed device type, server timestamp (never trust client clock)
- [ ] `omnirec-personalize-starter`: `PersonalizeProperties`, `AmazonPersonalizeProvider` (event mapper → `PutEvents`, `getRecommendations` → Campaign ARN), `@ConditionalOnProperty` auto-config
- [ ] `omnirec-algolia-starter`: `AlgoliaProperties`, `AlgoliaSearchProvider` (search + indexItems), auto-config
- [ ] Synchronous dispatch from facade to active providers (no queue yet)

Proof of DX:
- [ ] `examples/nextjs-demo-store` + `docker-compose.yml` — clone, `pnpm install`, `docker-compose up`, working search + recommendations in **under 10 minutes** with a documented setup doc. Treat this number as a hard acceptance bar, not aspirational.

**Exit criteria:** a developer with no prior context can follow the example app's README and see a working recommendation + search flow without reading any Omnirec source code.

### Phase 2 — Second provider + async pipeline (weeks 4–5)
- [ ] Swap synchronous dispatch for an async queue. Use **Redis Streams** (already needed for Phase 3) or SQS — not Kafka, unless there's already a throughput need for it. Picking the heavier tool now is a speed tax with no current payoff.
- [ ] `omnirec-google-recai-starter`, mirroring the Personalize starter's shape exactly
- [ ] **Contract test in CI**: one fixture canonical event run through every registered provider's mapper, asserting no required field is silently dropped. This is what catches schema drift before it ships.
- [ ] Confirm zero changes were needed in `PersonalizationService`, `IngestionController`, or any frontend package to add this provider. If something had to change, fix the abstraction, don't just note it.

**Exit criteria:** flipping `google-rec-ai.enabled: true` in YAML (with the starter jar on the classpath) is the entire integration — same claim made in the design doc, now actually verified by a passing test + working example.

### Phase 3 — Redis-backed recently-viewed (week 6)
- [ ] `omnirec-redis-starter`: `RedisCacheProvider` implementing `CacheProvider`
- [ ] `PRODUCT_VIEWED` event → capped per-user list in Redis (`LPUSH` + `LTRIM`) → `personalizationService.getRecentlyViewed(userId)`
- [ ] `<RecentlyViewedCarousel>` in `@omnirec/react-ui`

**Exit criteria:** recently-viewed works standalone with *no* Personalize/Algolia/Google config present — proves `CacheProvider` doesn't secretly depend on the recommendation stack.

### Phase 4 — DX polish & release readiness (weeks 7–8)
- [ ] `npx create-omnirec-app` scaffolding CLI (Next.js + Spring Boot starter combo, provider flags)
- [ ] Consent gate: frontend plugins check consent state before firing; backend rejects/redacts ungated implicit events
- [ ] Multi-tenant credential storage (DB-backed config, not static YAML) — at minimum, design the interface so static YAML is just the default `TenantConfigProvider` implementation
- [ ] Docs site generated from `schema/` + each starter's README
- [ ] Publish: npm packages under `@omnirec/*`, Java artifacts to GitHub Packages first (Maven Central migration once the API is stable — don't block v1 on Central's release process)

**Exit criteria:** v1.0.0 tag — schema frozen and versioned, four providers working, example app deployed publicly, README setup time still under 10 minutes.

---

## 3. What deliberately gets deferred past v1

- Kafka (Redis Streams/SQS is enough until real throughput data says otherwise)
- Seasonal-trend contextual signal (needs a real data source/model — stub as a static rule table for v1, don't build a pipeline for it yet)
- A/B running multiple recommendation providers in parallel (the facade should *allow* this architecturally, but building the experiment framework itself is post-v1 scope)

Keeping these out of v1 is itself a speed decision — each one is real work that doesn't block anyone from adopting the core framework.

---

## 4. Non-negotiables (carried over, restated as acceptance checks)

- Frontend never imports a provider SDK — only `@omnirec/core`'s transport talks to the backend.
- Every event, from any plugin, is validated against `schema/canonical-event.schema.json` before it reaches a provider mapper.
- Contextual fields (geo, device, timestamp) are always server-derived, never accepted from the client payload.
- No provider starter ships without an `InMemory`/fake counterpart usable in tests and local dev without real credentials.
