// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.worker.EventStorageDestination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service with historical storage off — the default, from the real
 * application.yml. omnirec-event-storage is on the classpath, as it is in the
 * deployed jar, and must contribute nothing: no pool, no DataSource, no worker,
 * no endpoint. No database exists in this test; if anything tried to reach one,
 * startup would fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.tenants.demo-store.secret-key=sk_test_demo_store_secret",
        "omnirec.events.rate-limit.enabled=false"
})
class StorageDisabledTest {

    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new ArrayList<>();

        @Override public String id() { return "recording"; }

        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        RecordingDestination recordingDestination() {
            return new RecordingDestination();
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingDestination destination;

    @Autowired
    private List<EventDestination> destinations;

    @Test
    void noStorageBeanExistsAndNoDatabaseIsConfigured() {
        assertEquals(0, context.getBeanNamesForType(EventStore.class).length);
        assertEquals(0, context.getBeanNamesForType(StorageDatabase.class).length);
        assertEquals(0, context.getBeanNamesForType(EventStorageDestination.class).length);
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                "nothing may have configured a database connection");
    }

    @Test
    void theStorageWorkerIsNotADestination() {
        assertTrue(destinations.stream().noneMatch(d -> d.id().equals(EventStorageDestination.ID)));
    }

    @Test
    void theEventApiAndExistingDestinationsStillWork() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_demo_store")
                        .content("""
                                {"events":[{"eventId":"evt_disabled_1","eventType":"product_viewed",
                                  "timestamp":"2026-01-01T12:00:00Z",
                                  "identity":{"anonymousId":"anon_1","sessionId":"s1"},
                                  "commerce":{"productId":"p1"}}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertTrue(destination.received.stream().anyMatch(e -> e.eventId().equals("evt_disabled_1")));
    }

    @Test
    void theHistoryEndpointDoesNotExist() throws Exception {
        mockMvc.perform(get("/v1/customers/user_1/events")
                        .header("Authorization", "Bearer sk_test_demo_store_secret"))
                .andExpect(status().isNotFound());
    }
}
