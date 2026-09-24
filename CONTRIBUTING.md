# Contributing to Omnirec

Thank you for your interest in contributing. This document describes how to set
up a development environment, the standards a change is expected to meet, and how
to submit it.

By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).
Contributions are licensed under the project's [MIT License](LICENSE).

## Reporting problems

- **Bugs and feature requests:** open an issue. For a bug, include the version,
  what you expected, what happened, and the smallest reproduction you can
  provide. If the problem involves event delivery, include the destination and
  the relevant log lines (event identifiers and field names only; never include
  credentials or customer data).
- **Security vulnerabilities:** do not open a public issue. Follow
  [SECURITY.md](SECURITY.md).
- **Questions about design:** read [docs/guide.md](docs/guide.md) and
  [docs/architecture.md](docs/architecture.md) first, then open a discussion or
  issue.

## Development environment

Requirements:

| Tool | Version |
| --- | --- |
| Node.js | 20 (the version used in continuous integration) |
| npm | 11 (see `packageManager` in `package.json`) |
| JDK | 17 (Temurin in continuous integration) |
| Maven | 3.9 or later |
| Docker | Required for the integration tests and the end-to-end script |

```bash
git clone https://github.com/Kevin200307/omniRec.git
cd omniRec
npm install
```

### Running the checks

These are the same checks that continuous integration runs. A change should pass
all of them before review.

```bash
# Frontend: build, type-check, and test every package
npx turbo run build typecheck test

# Confirm that no provider credential or SDK reaches a browser bundle (run after a build)
node scripts/verify-bundle-security.mjs

# Backend: build and test every module
cd backend && mvn clean install
```

The backend suite includes tests that start a real RabbitMQ broker and a real
Redis instance using Testcontainers. Without a running Docker daemon they are
skipped rather than failed, so **confirm the skipped count is 0** before
concluding that a change to queueing or shared state is verified.

For changes that affect the pipeline as a whole, also run the live end-to-end
script, which needs Docker and free local ports 25672, 26379, 4566, and 8124:

```bash
bash scripts/e2e/run.sh
```

### Running the stack locally

```bash
docker-compose up -d
npx turbo run dev --filter=nextjs-demo-store
```

See the [guide](docs/guide.md#3-running-locally) for details.

## Repository structure

See the layout in the [README](README.md#repository-layout) and the module
descriptions in [docs/architecture.md](docs/architecture.md). The most important
boundary is this one:

> `omnirec-commerce-core`, `omnirec-event-api`, `omnirec-event-processing`, and
> both SDKs must not depend on any provider SDK. Provider-specific code belongs
> in a destination module behind the `EventDestination` interface.

A change that breaks this boundary will not be accepted. The build enforces it,
so a provider import in those modules does not compile.

## Standards for changes

### Correctness must be demonstrated

- Every behaviour change needs a test that **fails without the change**. Before
  fixing a bug, write the test that reproduces it and confirm it fails.
- Do not describe something as working because it compiles or because a mock
  returned the expected value. Where a change depends on RabbitMQ, Redis, or a
  provider, exercise the real component where practicable.
- Anything that has not been exercised against the real external system must be
  documented as unverified.
- When a change touches configuration or wiring, add or extend a test that boots
  the assembled application, not only the module. See `RecentlyViewedFeedTest`
  for the reason.

### The canonical event has three definitions

The event shape is defined in `packages/commerce-web/src/events/types.ts`, in
`io.omnirec.commerce.model.CommerceEvent`, and in
`schema/commerce-event.schema.json`. A change to the event model must update all
three. `CanonicalSchemaContractTest` fails the build if they diverge. The same
applies to the list of blocked sensitive field names and the list of URL
parameters that are removed.

### Security and privacy rules

These are project invariants. A change that violates one will be declined.

- Provider credentials (AWS keys, Google service-account keys, and similar) must
  never appear in browser code, SDK code, examples, or default configuration.
- Card numbers, security codes, passwords, authentication tokens, and private
  keys must never be collected. Sensitive fields are rejected, not redacted.
- Neither an IP address nor a browser fingerprint may be used as identity.
- Previously captured events must never be rewritten when an anonymous visitor
  is identified.
- Logs must not contain payloads or field values; event identifiers and field
  names only.
- The project deliberately excludes dashboards, reporting interfaces, billing,
  and machine learning. Proposals in those areas are out of scope.

### Adding a destination

Implement `EventDestination`, register it from its own auto-configuration
guarded by an `omnirec.destinations.<id>.enabled` property that defaults to
`false`, and keep the mapping in a separate class from the network call so it can
be tested without credentials. Follow the contract in
[docs/guide.md](docs/guide.md#9-extending-the-system): idempotent, throw on
transient failure, use `DestinationException.permanent(...)` for failures a retry
cannot fix, and never modify the event.

Check the mapping against the provider's current API documentation and cite the
limits you relied on. Provider limits change; the tests should name the rule they
encode.

### Code style

- Match the style of the surrounding code, including comment density and naming.
- Comments explain why, not what. Where a decision is not obvious, state the
  failure it prevents.
- Test names describe behaviour, for example
  `anAnonymousVisitorSendsNoUserIdAtAll`, rather than the method under test.
- Java: standard Spring Boot conventions; constructor injection; conditional
  beans use `@ConditionalOnMissingBean` so applications can replace them.
- TypeScript: strict mode; the browser SDK must not gain a framework
  dependency.
- Keep the frontend bundle free of server-only dependencies.

### Documentation

Update the documentation in the same change as the code. In particular:

- behaviour or configuration: [docs/configuration.md](docs/configuration.md) and
  the relevant reference document;
- event types or validation: [docs/event-schema.md](docs/event-schema.md);
- test counts and new suites: [docs/testing.md](docs/testing.md) and the README;
- user-visible changes: [CHANGELOG.md](CHANGELOG.md).

Documentation is written in a formal, precise register, in plain ASCII text
without decorative symbols or emoji.

## Submitting a change

1. Fork the repository and create a branch from `main`. Use a descriptive name,
   for example `fix/retry-tier-ttl`.
2. Make focused commits. A commit message should explain what changed and why in
   the imperative mood, for example "Skip dwell updates in the recently-viewed
   destination".
3. Run the checks above.
4. Open a pull request against `main` and describe:
   - the problem and the approach taken;
   - how you verified it, and what you were unable to verify;
   - any change to the event schema, delivery guarantees, or configuration.
5. Respond to review comments. Maintainers may ask for additional tests or
   documentation.

Keep pull requests small enough to review. A refactor and a behaviour change
belong in separate pull requests.

## Releases

The project follows [Semantic Versioning](https://semver.org). The event
`schemaVersion` changes only for a breaking change to the event shape; adding an
optional field is not breaking. Notable changes are recorded in
[CHANGELOG.md](CHANGELOG.md).
