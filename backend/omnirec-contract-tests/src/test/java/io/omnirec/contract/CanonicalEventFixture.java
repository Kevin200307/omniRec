// SPDX-License-Identifier: Apache-2.0
package io.omnirec.contract;

import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.EventCategory;
import io.omnirec.core.model.EventContext;
import io.omnirec.core.model.EventType;

import java.time.Instant;
import java.util.Map;

/** A single representative event exercised against every provider's mapper. Extend this, don't fork it, if a new required field is added to the canonical schema. */
final class CanonicalEventFixture {

    static CanonicalEvent productClicked() {
        return new CanonicalEvent(
                "evt-fixture-1",
                "tenant-1",
                "user-42",
                "anon-99",
                "session-7",
                EventType.PRODUCT_CLICKED,
                EventCategory.IMPLICIT,
                Map.of("productId", "sku-123"),
                new EventContext("127.0.0.1", "desktop", "US", "Seattle", Instant.parse("2026-01-01T00:00:00Z"), null)
        );
    }

    private CanonicalEventFixture() {}
}
