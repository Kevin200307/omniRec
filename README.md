# Omnirec

An open-source, provider-agnostic personalization layer for e-commerce. Drop-in frontend tracking (dwell time, scroll depth, cart abandonment, search) and a Spring Boot backend that routes signals to **AWS Personalize**, **Google Recommendations AI**, **Algolia**, and **Redis** (recently-viewed) — through one interface, with zero vendor lock-in and zero code changes to switch providers.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the full architecture rationale and phased roadmap. This README covers what's built and how to run it.

## What's here

```
packages/
  core/                    @omnirec/core — tracker, identity, consent, event queue, plugins
  react/                   @omnirec/react — OmnirecProvider, useOmnirec()
  react-ui/                @omnirec/react-ui — <SearchBar>, <ProductImpression>, carousels
  create-omnirec-app/      npx create-omnirec-app scaffolding CLI

backend/                   Java / Spring Boot, Maven multi-module
  omnirec-core/            Canonical event model, provider interfaces, PersonalizationService facade
  omnirec-web/             REST layer (/v1/events, /v1/search, /v1/recommendations, /v1/recently-viewed)
  omnirec-personalize-starter/    AWS Personalize
  omnirec-algolia-starter/        Algolia
  omnirec-google-recai-starter/   Google Recommendations AI (Cloud Retail API)
  omnirec-redis-starter/          Redis-backed cache / recently-viewed
  omnirec-contract-tests/         Enforces that every provider mapper preserves canonical event fields
  omnirec-demo-app/               Runnable Spring Boot app bundling every starter

examples/nextjs-demo-store/       Full working example: Next.js + every component wired up
schema/canonical-event.schema.json   The single source of truth every plugin and provider mapper agrees on
```

## Quick start

**Backend + Redis** (everything else defaults to in-memory fakes, so this runs with zero credentials):

```bash
docker-compose up -d
```

**Frontend:**

```bash
npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store
```

Open http://localhost:3000 — see [examples/nextjs-demo-store/README.md](examples/nextjs-demo-store/README.md) for what you're looking at.

**Scaffold a new project** with only the providers you want:

```bash
node packages/create-omnirec-app/dist/index.js my-store --recommendation=aws-personalize --search=algolia
```

## Building from source

```bash
npm install && npx turbo run build     # JS packages
cd backend && mvn clean install        # Java backend + contract tests
```

## Design principle

The frontend never imports a provider SDK — every signal flows through the backend's `PersonalizationService` facade, which dispatches to whichever `RecommendationProvider` / `SearchProvider` / `CacheProvider` beans are active. Adding a provider is a new starter jar plus a config flag; removing one is deleting a dependency. See `IMPLEMENTATION_PLAN.md` for why this shape, and `omnirec-contract-tests` for how that invariant is enforced in CI rather than just documented.
