-- SPDX-License-Identifier: Apache-2.0
--
-- Indexes for the queries Omnirec actually runs, and no others. Every one of
-- these is paid for on every insert, and the storage worker is insert-heavy.
--
-- Customer history (GET /v1/customers/{id}/events) is two keyset scans merged:
--
--   1. the customer's authenticated events
--        WHERE tenant_id = ? AND user_id = ?
--        ORDER BY occurred_at DESC, event_id DESC
--   2. anonymous events from devices linked to the customer
--        WHERE tenant_id = ? AND user_id IS NULL AND anonymous_id IN (...)
--        ORDER BY occurred_at DESC, event_id DESC
--
-- event_id is the last key column in both so that the (occurred_at, event_id)
-- cursor is answered from the index in order, with no sort, even when many
-- events share a timestamp.

-- (1) Authenticated history. Partial: anonymous rows never match user_id = ?,
-- so they would only make the index bigger.
CREATE INDEX commerce_events_user_history_idx
    ON ${flyway:defaultSchema}.commerce_events (tenant_id, user_id, occurred_at DESC, event_id DESC)
    WHERE user_id IS NOT NULL;

-- (2) Anonymous history of linked devices. Partial for the opposite reason:
-- the query only wants rows captured without a user.
CREATE INDEX commerce_events_anonymous_history_idx
    ON ${flyway:defaultSchema}.commerce_events (tenant_id, anonymous_id, occurred_at DESC, event_id DESC)
    WHERE user_id IS NULL;

-- Resolves customer -> linked anonymous ids (the IN (...) above). The primary
-- key covers the other direction.
CREATE INDEX identity_links_user_idx
    ON ${flyway:defaultSchema}.identity_links (tenant_id, user_id);

-- Evaluated and deliberately NOT created:
--
--   (tenant_id, event_type, occurred_at DESC)
--     No query reads events by type across a whole tenant. eventType is a
--     filter inside one customer's history, which (1)/(2) already narrow to a
--     handful of rows. Add it together with a tenant-wide query, not before.
--
--   product_id
--     Nothing queries by product today. The column exists so that such an
--     index can be added later without a backfill.
