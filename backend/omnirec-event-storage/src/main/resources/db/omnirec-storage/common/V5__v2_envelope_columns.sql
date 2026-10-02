-- SPDX-License-Identifier: Apache-2.0
-- Envelope v2: the payload moves from the flat v1 "commerce" object to "data"
-- blocks, and events carry their version, kind and source.
--
-- Rows written before this migration keep data = {}; the store reads them
-- through the v1 "commerce" column, which it keeps writing for one release so
-- v1 readers of the history API are unaffected.
ALTER TABLE ${flyway:defaultSchema}.commerce_events
    ADD COLUMN event_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN kind          TEXT    NOT NULL DEFAULT 'standard',
    ADD COLUMN source        TEXT,
    ADD COLUMN data          JSONB   NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN ${flyway:defaultSchema}.commerce_events.data IS
    'Envelope v2 payload (product, cart, order, ... blocks). Empty for rows written before V5; read those from commerce.';
