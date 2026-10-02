# Custom events

The catalog covers about 200 commerce events. When none fits, define your own
in a tracking plan, `omnirec.plan.yaml`. The collector validates custom events
just as strictly as standard ones, the SDKs type them, and CI catches breaking
changes.

## The plan

```yaml
vocabularies:
  size_unit: [cm, inch]

events:
  size_guide_opened:
    description: "Shopper opened the size guide on a product page."
    sources: [browser]               # browser, server, webhook, derived, import (default: browser, server)
    blocks: [product]                # reuse catalog blocks
    aliases: [size_chart_opened]
    version: 1                       # raise it to make a breaking change on purpose
    properties:
      product.id: { required: true } # refine a block field: constraints only
      unit: { type: enum, vocabulary: size_unit }       # inline field in data
      position: { type: integer, minimum: 1 }
```

The rules match the collector's exactly, and `omnirec validate` applies them:

- Names are `snake_case`, 3 to 64 characters, and must not clash with a
  catalog event, alias or vocabulary.
- Inline fields are `lowerCamelCase` and need a `type`: `string`, `integer`,
  `number`, `boolean`, `timestamp`, `money`, `enum` (with a `vocabulary`),
  `array` (with `items`) or `object` (with `fields`).
- `block.field` refines a field of a block the event lists. It may set
  `required`, `minimum`, `maximum`, `minItems`, `maxLength`, `pattern` and
  `description`, never the type.

## Using it

**Collector.** Load the plan per tenant (`omnirec.events.tenants.<id>.plan-paths`)
or for the default tenant (`omnirec.events.default-plan-paths`). Startup fails
on an invalid plan. `GET /v1/catalog` returns the tenant's catalog merged with
its plan.

**Browser and Node.** `omnirec generate` writes `omnirec.d.ts`, after which
`track()` checks custom events like standard ones:

```ts
omnirec.track("size_guide_opened", { product: { id: "P1" }, unit: "cm" }); // ok
omnirec.track("size_guide_opened", { unit: "mm" });                        // type error
```

**HTML.** Use `data-omnirec-event="size_guide_opened"`; the event is validated by
the collector.

**Java.** `omnirec generate` writes `OmnirecEvents.java`:
`tracker.track(OmnirecEvents.SIZE_GUIDE_OPENED, Map.of(...))`.

**Locally.** `omnirec dev` validates against the plan and reloads it when you
save.

## Unplanned events

An event that is in neither the catalog nor the plan is accepted in
`permissive` mode (the default) and flagged `unplanned`. It reaches storage, so
you can find it, but never a provider, so a typo cannot train a recommendation
model. In `strict` mode it is rejected.

## Changing a plan safely

```bash
omnirec validate --against origin/main     # a git ref or a file
```

Additive changes pass: new events, new optional fields, fields that become
optional, and new vocabulary values. These changes fail unless the event's
`version` is raised:

| Change | Why it breaks |
| --- | --- |
| A field removed | Stored events and dashboards still read it |
| A field made required, or a new required field | Senders not yet updated are rejected |
| A type changed | Old and new values cannot be compared |
| A vocabulary value removed | Events carrying it are rejected |

Removing an event always fails. Deprecate it instead, by stopping sending it.
Exit codes: `0` fine, `1` problems or unapproved breaking changes, `2` no plan
or no comparison possible.
