# @omnirec/cli

Command-line tools for omniRec: set up a project, check your tracking plan, and
run a local collector while you build.

```sh
npx @omnirec/cli init       # starter omnirec.plan.yaml + setup for your framework
npx @omnirec/cli dev        # local collector on http://localhost:8124
npx @omnirec/cli generate   # types for your custom events
npx @omnirec/cli validate   # check the plan; in CI: --against origin/main
```

## `omnirec init`

Detects Next.js, React, Vue, plain JavaScript or TypeScript, Spring Boot
(`pom.xml` or `build.gradle`), or plain HTML. It writes a starter
`omnirec.plan.yaml` and prints the install command and the code to paste. It
never replaces an existing plan unless you pass `--force`.

| Option | |
| --- | --- |
| `--framework <f>` | `next`, `react`, `vue`, `web`, `spring` or `html`, instead of detection |
| `--endpoint <url>` | Collector URL used in the snippet (default `http://localhost:8124`) |
| `--force` | Replace an existing plan |

## `omnirec dev`

A collector for development, with no Java, Docker or key. It accepts
everything the Event API accepts:

- `POST /v1/events` and `POST /v1/events/batch` (JSON or `sendBeacon`'s
  `text/plain`)
- `POST /v1/identify`
- `GET /v1/catalog`

Each event is validated against the standard catalog and your plan, using the
collector's rules, and printed:

```
✓ add_to_cart (alias of product_added_to_cart) product=P1 anon=4f1c2a9e-0d3b
✗ rejected purchase_completed
    data.order.currency: currency must be a 3-letter ISO 4217 code
✓ promo_banner_seen  unplanned: not in the catalog or your plan, stored but never sent to providers
```

`http://localhost:8124/` shows the same list live, with each payload. The plan
is reloaded when you save it. Any origin may send, and events go nowhere else.

| Option | |
| --- | --- |
| `--port <n>` | Default 8124 |
| `--plan <file>` | Default `omnirec.plan.yaml`, when present |

## `omnirec generate`

In your project, it reads `omnirec.plan.yaml` and writes:

- `omnirec.d.ts` when there is a `package.json`. This file teaches `track()`
  your custom events, with their fields and allowed values.
- `src/main/java/omnirec/OmnirecEvents.java` when there is a `pom.xml` or
  `build.gradle`. Choose the package with `--java-package`.

`--check` reports out-of-date files and exits 1, for CI. Inside the omniRec
repository, `generate` instead regenerates the SDKs, schema and docs from
`catalog/`.

## `omnirec validate`

Lints the plan with the collector's own rules. These include unknown keys,
names that clash with the catalog, blocks and fields that do not exist,
vocabularies, and constraints that do not fit the type.

With `--against <file or git ref>`, it also lists what changed. It exits 1 on a
breaking change: a removed event or field, a field that became required, a
changed type, or a removed vocabulary value. Raise the event's `version` to
accept a breaking change on purpose. Removing an event is never accepted.

```yaml
# .github/workflows/plan.yml
- run: npx @omnirec/cli validate --against origin/main
```

| Exit code | Meaning |
| --- | --- |
| 0 | Valid, and no unapproved breaking changes |
| 1 | Plan problems or unapproved breaking changes |
| 2 | No plan, or the comparison could not be made |
