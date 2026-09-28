-- SPDX-License-Identifier: Apache-2.0
--
-- The one historical event table. Every event type lands here: the columns are
-- the fields every CommerceEvent has (or that history queries filter on), and
-- everything type-specific stays in JSONB exactly as the canonical model holds
-- it. A new event type therefore never needs a new table or a migration.
--
-- Uniqueness is deliberately NOT declared here. It is provider-specific:
--   postgres  -> PRIMARY KEY (tenant_id, event_id)                 (V4, postgres/)
--   timescale -> UNIQUE (tenant_id, event_id, occurred_at)         (V4, timescale/)
-- because a TimescaleDB hypertable requires every unique index to include the
-- time column.
--
-- Rows are never updated after insert. An identify does not rewrite earlier
-- anonymous rows; attribution is a join through identity_links (V2).

CREATE TABLE ${flyway:defaultSchema}.commerce_events (
    -- Tenant first: every query is tenant-scoped, and so is uniqueness. The
    -- eventId is client-supplied, so a globally unique event_id would let one
    -- tenant suppress another tenant's event by reusing its id.
    tenant_id       TEXT        NOT NULL,
    event_id        TEXT        NOT NULL,
    -- Wire name ("product_viewed"), not the Java enum constant.
    event_type      TEXT        NOT NULL,
    schema_version  TEXT        NOT NULL,
    -- CommerceEvent.timestamp: when it happened on the client (clamped by the
    -- normalizer if the client clock is absurd). The time dimension.
    occurred_at     TIMESTAMPTZ NOT NULL,
    -- CommerceEvent.receivedAt: when the Event API accepted it.
    received_at     TIMESTAMPTZ,
    -- When the storage worker wrote it. Operational only; never exposed.
    stored_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- EventIdentity, as captured. user_id stays NULL on an anonymous event
    -- forever, even after the visitor identifies.
    anonymous_id    TEXT,
    user_id         TEXT,
    session_id      TEXT,

    -- Denormalised from commerce.productId for filtering. The full commerce
    -- payload (price, items, order id...) stays in `commerce`.
    product_id      TEXT,

    commerce        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    properties      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    context         JSONB       NOT NULL DEFAULT '{}'::jsonb
);

COMMENT ON TABLE ${flyway:defaultSchema}.commerce_events IS
    'Append-only history of canonical CommerceEvents, written asynchronously by the Omnirec storage worker.';
