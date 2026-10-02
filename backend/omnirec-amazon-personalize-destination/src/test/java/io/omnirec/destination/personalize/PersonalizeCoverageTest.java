// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.personalize;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.generated.StandardEvents;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the Personalize allowlist in step with the catalog. */
class PersonalizeCoverageTest {

    @Test
    void everyInteractionIsACatalogEvent() {
        Set<String> unknown = new TreeSet<>(AmazonPersonalizeDestination.INTERACTIONS);
        unknown.removeAll(StandardEvents.ALL);
        assertTrue(unknown.isEmpty(), "renamed or removed catalog events still listed: " + unknown);
    }

    @Test
    void neverSendsControlOrDerivedEvents() {
        for (String name : AmazonPersonalizeDestination.INTERACTIONS) {
            var definition = EventRegistry.standard().find(name).orElseThrow();
            assertFalse(definition.control(), name);
        }
        assertFalse(AmazonPersonalizeDestination.INTERACTIONS.contains(StandardEvents.IDENTIFY));
        assertFalse(AmazonPersonalizeDestination.INTERACTIONS.contains(StandardEvents.SUPPORT_TICKET_CREATED));
    }
}
