-- SPDX-License-Identifier: Apache-2.0
--
-- anonymous device -> customer, per tenant.
--
-- The mapping is tenant-scoped: tenant_A's anon_123 and tenant_B's anon_123
-- are unrelated visitors and share nothing.
--
-- One row per (tenant, anonymous id): a device belongs to one customer at a
-- time. When a device is re-linked to a different customer (a shared device, an
-- account switch) the most recent link wins, which matches
-- IdentityLinkStore.resolveUserId. "Most recent" is by event time, not arrival
-- time: the storage worker only moves a row to a new user when the linking
-- event is newer than linked_at, so an old event redelivered late cannot undo
-- a newer link.
--
-- Historical events are never rewritten when a link appears; customer history
-- reads through this table instead.

CREATE TABLE ${flyway:defaultSchema}.identity_links (
    tenant_id       TEXT        NOT NULL,
    anonymous_id    TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    -- The earliest event time at which this anonymous id was seen linked to anyone.
    first_seen_at   TIMESTAMPTZ NOT NULL,
    -- The latest event time at which the current anonymous_id -> user_id
    -- mapping was asserted. A different user only replaces it with a newer event.
    linked_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, anonymous_id)
);

COMMENT ON TABLE ${flyway:defaultSchema}.identity_links IS
    'Tenant-scoped anonymousId -> userId links, used to attribute anonymous history at query time.';
