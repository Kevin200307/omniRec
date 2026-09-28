-- SPDX-License-Identifier: Apache-2.0
--
-- TimescaleDB only (omnirec.storage.provider=timescale). The application checks
-- that the extension is available before running this, so a plain PostgreSQL
-- server fails with a readable message rather than half a migration.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Idempotency. A hypertable requires every unique index to include the
-- partitioning column, so the key is (tenant_id, event_id, occurred_at) rather
-- than the postgres provider's (tenant_id, event_id). For redelivery - the case
-- this exists for - the difference does not matter: a redelivered message
-- carries the same timestamp, so it conflicts and is skipped.
ALTER TABLE ${flyway:defaultSchema}.commerce_events
    ADD CONSTRAINT commerce_events_tenant_event_key UNIQUE (tenant_id, event_id, occurred_at);

-- occurred_at is the time dimension. Chunks are what retention drops, whole,
-- instead of deleting row by row. The chunk interval comes from
-- omnirec.storage.timescale.chunk-interval and only applies at creation.
SELECT create_hypertable(
    '${flyway:defaultSchema}.commerce_events',
    'occurred_at',
    chunk_time_interval => INTERVAL '${chunkTimeInterval}',
    if_not_exists => TRUE,
    migrate_data => TRUE
);
