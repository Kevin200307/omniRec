-- SPDX-License-Identifier: Apache-2.0
--
-- Plain PostgreSQL (local, Neon, RDS, Supabase, ...). Nothing here needs an
-- extension.
--
-- Only run for omnirec.storage.provider=postgres. The timescale provider has
-- its own V4; Flyway refuses to start if a database created by one provider is
-- opened by the other, which is intended: switching an existing database
-- between providers is a manual migration (see docs/event-storage.md).

-- Idempotency. RabbitMQ is at-least-once, so the storage worker will see some
-- events twice; INSERT ... ON CONFLICT DO NOTHING against this key makes the
-- second write a no-op. Tenant-scoped because eventId is client-supplied.
ALTER TABLE ${flyway:defaultSchema}.commerce_events
    ADD CONSTRAINT commerce_events_pkey PRIMARY KEY (tenant_id, event_id);

-- Retention. The purge job deletes by occurred_at in batches; a BRIN index is
-- a few pages for an append-mostly, time-correlated table, where a B-tree on
-- occurred_at would cost as much as the table's other indexes.
CREATE INDEX commerce_events_occurred_at_brin
    ON ${flyway:defaultSchema}.commerce_events USING brin (occurred_at);
