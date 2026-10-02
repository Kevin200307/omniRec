// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.googleretail;

import io.omnirec.commerce.catalog.generated.StandardEvents;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Every event the Google Retail mapping names must exist in the catalog. */
class GoogleRetailCoverageTest {

    @Test
    void everyMappedEventIsACatalogEvent() {
        Set<String> unknown = new TreeSet<>(GoogleRetailEventMapper.handledEvents());
        unknown.removeAll(StandardEvents.ALL);
        assertTrue(unknown.isEmpty(), "renamed or removed catalog events still mapped: " + unknown);
        assertFalse(GoogleRetailEventMapper.handledEvents().isEmpty());
    }
}
