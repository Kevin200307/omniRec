# Omnirec Demo Store

Proof-of-DX example from `IMPLEMENTATION_PLAN.md`'s Phase 1 exit criteria: a stranger should be able to clone this repo and see working search + recommendations in under 10 minutes, without reading any Omnirec source code.

## Setup

From the **repo root** (not this directory):

```bash
npm install
docker-compose up -d          # starts the Spring Boot backend (port 8080) + Redis
```

Then, in this directory:

```bash
cp .env.local.example .env.local
```

Then, from the **repo root**:

```bash
npx turbo run dev --filter=nextjs-demo-store
```

Open http://localhost:3000.

## What you're looking at

- **Search bar** — calls the backend's `/v1/search`. With no search provider configured it's served by the in-memory fallback (empty until you call `/v1/index` — this demo doesn't seed one, so try clicking products instead).
- **Product grid** — each card is wrapped in `<ProductImpression>`. Click one: that's a `PRODUCT_CLICKED` + `PRODUCT_VIEWED` event, captured automatically (dwell time too, if you leave the card on screen a moment).
- **Add to cart / Wishlist** — `useOmnirec().trackCartAdd()` and an explicit `track()` call.
- **Recommended for you** — starts empty; click a few products, then reload. The in-memory `RecommendationProvider` fake ranks by click count until a real provider (AWS Personalize or Google Rec AI) is configured.
- **Recently viewed** — backed by the in-memory `CacheProvider` fake, or Redis if you set `REDIS_ENABLED=true` (already on by default in `docker-compose.yml`).

## Swapping in a real provider

Edit `backend/omnirec-demo-app/src/main/resources/application.yml` (or set the matching environment variables) — e.g. to turn on AWS Personalize:

```yaml
omnirec:
  recommendation:
    aws-personalize:
      enabled: true
      access-key: ...
      secret-key: ...
      campaign-arn: ...
      tracking-id: ...
```

No frontend or controller code changes — see the root `IMPLEMENTATION_PLAN.md` and the design doc for why.
