-- SPDX-License-Identifier: Apache-2.0
--
-- Customer deletion (DELETE /v1/customers/{id}) leaves a tombstone per erased
-- user id and anonymous id, so events still arriving for them are dropped
-- instead of recreating the deleted history.
--
-- Only a fingerprint is stored: SHA-256 of the tenant and the id, never the id
-- itself. It recognises a returning id and identifies no one.

CREATE TABLE ${flyway:defaultSchema}.erasure_tombstones (
    tenant_id    TEXT        NOT NULL,
    fingerprint  TEXT        NOT NULL,
    erased_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, fingerprint)
);

COMMENT ON TABLE ${flyway:defaultSchema}.erasure_tombstones IS
    'Fingerprints (sha256 of tenant and id) of erased customer and device ids; events for them are dropped.';
