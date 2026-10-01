# Event catalog

This folder is the single source of truth for OmniRec's standard events. The
TypeScript types, the Java constants, the JSON Schema enum, the runtime catalog
and the event docs are all generated from it.

```bash
npm run catalog:generate   # regenerate everything after editing catalog/
npm run catalog:check      # what CI runs: fails if generated files are stale
```

Never edit generated files by hand. CI fails if they differ from what the
catalog produces.

## Layout

```
catalog/
  catalog.yaml                    catalog version, envelope schema version, domains
  blocks/<block>.yaml             reusable groups of fields
  vocabularies/<vocabulary>.yaml  controlled value lists for enum fields
  events/<domain>/<event>.yaml    one file per event, in its domain's folder
  envelope/v1.schema.json         hand-written v1 envelope; the generator fills in the event-name enum
```

Every YAML file starts with `# SPDX-License-Identifier: Apache-2.0`.

## Naming

Event, block, vocabulary and domain names match `^[a-z][a-z0-9_]{2,63}$`.
Events use `object_action` style, for example `product_viewed` or `cart_abandoned`.
A file is named after what it defines: `events/cart/cart_viewed.yaml` defines
`cart_viewed` in domain `cart`. The loader rejects any mismatch.

## `catalog.yaml`

```yaml
catalogVersion: 1          # bump when the catalog changes in a way consumers notice
schemaVersion: "1.0"       # envelope version the SDKs send
domains:                   # order here is the order in generated code and docs
  - id: cart
    title: Cart
    description: Changes to the shopping cart.
```

## Blocks

A block is a named group of fields that many events share.

```yaml
name: product
description: A product as shown to the shopper.
fields:
  id:       { type: string, description: Product identifier. }
  price:    { type: money, minimum: 0 }
  currency: { type: string, pattern: "^[A-Z]{3}$" }
  tags:     { type: array, items: { type: string } }
```

Field names are lowerCamelCase.

## Field types and constraints

| Type | Meaning | Allowed constraints |
|---|---|---|
| `string` | Text | `maxLength`, `pattern` |
| `integer` | Whole number | `minimum`, `maximum` |
| `number` | Decimal number | `minimum`, `maximum` |
| `money` | Decimal amount as number or decimal string, never float arithmetic server-side | `minimum`, `maximum` |
| `boolean` | `true` or `false` | |
| `timestamp` | ISO-8601 date-time string | |
| `enum` | One value from a vocabulary; needs `vocabulary: <name>` | |
| `array` | List; needs `items: <field>` | `minItems` |
| `object` | Nested fields under `fields:` | |

Any field may also set `required: true` and `description`.

## Vocabularies

```yaml
name: return_reason
description: Why a customer returned an item.
values:
  - value: damaged_or_defective
  - value: wrong_item
    description: A different product arrived.
```

## Events

```yaml
name: product_added_to_cart
domain: cart
version: 1                   # bump on any breaking change to this event
sources: [browser, server]   # browser | server | webhook | derived | import
description: "A product was added to the cart."
blocks: [commerce]           # blocks whose fields this event carries
properties:
  commerce.productId: { required: true }              # refines a block field
  commerce.quantity: { required: true, minimum: 1 }   # constraints merge onto the block's
  position: { type: integer, minimum: 1 }             # declares a new inline field
aliases: [add_to_cart_v0]    # older names, accepted and rewritten to this one
autocapture: false           # true when the browser SDK sends it without merchant code
control: false               # true for pipeline control events, never sent to providers
example:                     # checked against this definition on every load
  commerce: { productId: p123, quantity: 2 }
```

Rules for `properties`:

- A **dotted key** such as `commerce.productId` refines a field of a block the
  event lists under `blocks`. It may only add constraints, never change the type.
- `identity.userId` is the one envelope field an event may refine, for events
  that need a known customer.
- A **plain key** such as `position` declares a new field for this event only
  and must have a `type`.

Names and aliases share one namespace. An alias may never equal another event's
name or alias.

## What the loader checks

Every load reports all problems at once, with file and line where possible:

- each file matches the file-format schema, with no unknown keys
- names follow the naming rule and match their file and folder
- domains, blocks and vocabularies exist where referenced
- names and aliases are unique across the catalog
- constraints fit their field's type, and patterns are valid regular expressions
- each `example` has every required field, uses only known fields, and has the right types

## v1 compatibility

During v1 every event lists the `commerce` block, which mirrors the flat
`commerce` object of the wire format. Envelope v2 replaces it with `product`,
`cart`, `order` and similar blocks.
