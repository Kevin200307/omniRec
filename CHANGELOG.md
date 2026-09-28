# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- **Optional historical event storage** (`omnirec-event-storage`), off by
  default. A storage worker consumes its own RabbitMQ queue (`event-storage`,
  with retry tiers and a dead-letter queue) and writes every event through a new
  `EventStore` interface, acknowledging only after commit. `PostgresEventStore`
  works with any PostgreSQL (local, Neon, RDS, Supabase); `TimescaleEventStore`
  adds a hypertable and chunk-based retention. The schema consists of one
  `commerce_events` table with JSONB payloads and tenant-scoped
  `identity_links`, managed by Flyway. Writes are idempotent per tenant and
  event id. Retention is configurable.
- **Customer history API**, `GET /v1/customers/{customerId}/events`, with keyset
  pagination, time and event-type filters, and linked anonymous history.
  Authenticated with a new per-tenant `secret-key` or a multi-tenant platform key;
  publishable keys cannot read.
- Docker Compose profiles `postgres` and `timescale` for local storage
  development.

### Changed

- The `identify` control event is now published after its link is recorded, and
  `RabbitEventPublisher` routes control events only to destinations whose
  `supports()` accepts them. Provider destinations reject control events, so
  their queues are unchanged; with storage disabled, `identify` is published
  nowhere, as before.
- Relicensed from the MIT License to the Apache License, Version 2.0. Added a
  `NOTICE` file, an SPDX header on every source file
  (`scripts/add-license-headers.mjs`), license metadata in every `pom.xml` and
  `package.json`, and a `license-check` CI workflow.

## 0.1.0

Initial development release. It has not yet been published to a package registry,
and the provider adapters have not been executed against live provider accounts.

### Added

- **Canonical event model** with 37 event types and an `identify` control event,
  defined identically in TypeScript, Java, and a JSON Schema, with a contract test
  that fails the build if they diverge.
- **Browser SDK** (`@omnirec/commerce-web`) and React bindings
  (`@omnirec/commerce-react`): automatic identity, sessions, and page context;
  client-side validation; batching with offline buffering, retry with backoff and
  jitter, and `sendBeacon` on unload; foreground-only dwell-time measurement
  reported as an engagement update linked to its view.
- **Server-side SDK** (`commerce-tracker-spring-boot`) for authoritative business
  events, with asynchronous delivery, bounded retry, and deterministic event
  identifiers shared with the browser SDK so that a purchase reported from both
  sources is delivered once.
- **Event API** (`omnirec-event-api`, `omnirec-event-api-app`): a standalone
  service with API-key authentication and tenant isolation, payload and rate
  limits enforced before the body is parsed, per-event validation, URL
  sanitization, deduplication, and identity resolution.
- **Identity stitching**: an anonymous-to-user link stored separately from events,
  many devices per user, session rotation on sign-out or user change, and no
  rewriting of captured history.
- **RabbitMQ pipeline** (`omnirec-event-processing`): one queue set per
  destination, awaited publisher confirms, tiered retry queues, dead-letter
  queues, and lease-based idempotent delivery.
- **Destinations**: Amazon Personalize, Google Cloud Retail, and a
  recently-viewed feed for the serving side, each disabled by default.
- **Shared state** (`omnirec-redis-state`): Redis-backed deduplication and
  identity links for multi-instance deployments.
- **Observability**: Micrometer counters for ingestion and delivery, retry and
  dead-letter counters, a delivery latency timer, and queue-depth gauges.
- **Verification tooling**: a bundle security scan, Testcontainers-based suites
  against a real RabbitMQ and Redis, and a live end-to-end script.
- **Documentation**: guide, architecture, event schema, identity, SDK and
  provider references, RabbitMQ, security, configuration, testing,
  troubleshooting, and an audit report.

### Changed

- The Algolia connector and catalog synchronization were removed. Search was
  retained.
- The legacy unauthenticated ingestion endpoint on the serving API is now
  disabled by default (`omnirec.web.legacy-ingestion.enabled=false`).
- The legacy serving-side provider mappers were corrected to follow the current
  identity rules.

### Fixed

The audit recorded in [docs/AUDIT.md](docs/AUDIT.md) identified and resolved
approximately 35 defects before this release, including unawaited publisher
confirms, retry head-of-line blocking, a claim-before-work window that could lose
events, dwell updates that double-counted views, requests that were invalid for
the Personalize and Retail APIs, unenforced payload limits, and a rate limit
that could be bypassed. Each fix is covered by a test that fails against the
earlier behaviour.

### Known limitations

- The Amazon Personalize and Google Retail adapters are unverified against live
  accounts.
- No Azure destination is provided. Azure AI Personalizer retires on 1 October
  2026.
- Rate limiting is per instance.
- The server-side SDK queue is held in memory.

See [docs/guide.md](docs/guide.md#10-known-limitations) for the full list.
