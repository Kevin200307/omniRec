// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.identity.IdentityLinkStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /v1/customers/{id} against a real PostgreSQL: events across the
 * customer's devices are removed, links are forgotten, tombstones block late
 * events, and other customers are untouched. Events are delivered inline
 * (queue disabled) so each assertion sees storage immediately.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.events.tenants.tenant-a.api-key=pk_test_del_a",
        "omnirec.events.tenants.tenant-a.secret-key=sk_test_del_a_secret",
        "omnirec.events.tenants.tenant-b.api-key=pk_test_del_b",
        "omnirec.events.tenants.tenant-b.secret-key=sk_test_del_b_secret",
        "omnirec.storage.enabled=true",
        "omnirec.storage.provider=postgres",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerDeletionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("omnirec.storage.postgres.url", POSTGRES::getJdbcUrl);
        registry.add("omnirec.storage.postgres.username", POSTGRES::getUsername);
        registry.add("omnirec.storage.postgres.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IdentityLinkStore linkStore;

    private void send(String apiKey, String event, String anonymousId, String userId, String data) throws Exception {
        String body = """
                {"events":[{"eventId":"%s","event":"%s","schemaVersion":"2.0","source":"browser","timestamp":"%s",
                 "identity":{"anonymousId":"%s","userId":%s,"sessionId":"s_%s"},"context":{},"data":%s}]}
                """.formatted(UUID.randomUUID(), event, Instant.now(), anonymousId,
                userId == null ? "null" : "\"" + userId + "\"", anonymousId, data);
        mockMvc.perform(post("/v1/events").header("X-Omnirec-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    private void view(String apiKey, String anonymousId, String userId) throws Exception {
        send(apiKey, "product_viewed", anonymousId, userId, "{\"product\":{\"id\":\"p1\"}}");
    }

    private void identify(String apiKey, String anonymousId, String userId) throws Exception {
        mockMvc.perform(post("/v1/identify").header("X-Omnirec-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymousId\":\"%s\",\"userId\":\"%s\"}".formatted(anonymousId, userId)))
                .andExpect(status().isAccepted());
    }

    private long rows(String sql, String... binds) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) statement.setString(i + 1, binds[i]);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long eventsOf(String tenant, String anonymousId, String userId) throws Exception {
        return rows("SELECT count(*) FROM omnirec.commerce_events WHERE tenant_id = ? AND (anonymous_id = ? OR user_id = ?)",
                tenant, anonymousId, userId);
    }

    private JsonNode erase(String secret, String customer) throws Exception {
        String body = mockMvc.perform(delete("/v1/customers/" + customer).header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void erasesEventsAcrossDevicesAndLinksAndLeavesOthersAlone() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customer = "cust_" + suffix, laptop = "anon_laptop_" + suffix, phone = "anon_phone_" + suffix;
        String other = "other_" + suffix, otherDevice = "anon_other_" + suffix;

        view("pk_test_del_a", laptop, null);            // anonymous, before login
        identify("pk_test_del_a", laptop, customer);
        view("pk_test_del_a", laptop, customer);
        view("pk_test_del_a", phone, null);
        identify("pk_test_del_a", phone, customer);
        view("pk_test_del_a", otherDevice, other);      // someone else, same tenant
        view("pk_test_del_b", laptop, customer);        // same ids, another tenant

        assertTrue(eventsOf("tenant-a", laptop, customer) >= 3);
        assertEquals(2, linkStore.anonymousIdsFor("tenant-a", customer).size());

        JsonNode receipt = erase("sk_test_del_a_secret", customer);
        assertTrue(receipt.path("receiptId").asText().startsWith("del_"));
        assertEquals("tenant-a", receipt.path("tenantId").asText());
        assertEquals(2, receipt.path("devicesErased").asInt());
        assertTrue(receipt.path("eventsDeleted").asLong() >= 4);

        assertEquals(0, eventsOf("tenant-a", laptop, customer));
        assertEquals(0, eventsOf("tenant-a", phone, customer));
        assertEquals(0, rows("SELECT count(*) FROM omnirec.identity_links WHERE tenant_id = 'tenant-a' AND user_id = ?", customer));
        assertEquals(java.util.List.of(), linkStore.anonymousIdsFor("tenant-a", customer));
        assertTrue(linkStore.resolveUserId("tenant-a", phone).isEmpty());

        assertEquals(1, eventsOf("tenant-a", otherDevice, other), "other customers are untouched");
        assertEquals(1, eventsOf("tenant-b", laptop, customer), "other tenants are untouched");

        // Only fingerprints are kept.
        assertEquals(0, rows("SELECT count(*) FROM omnirec.erasure_tombstones WHERE fingerprint IN (?, ?)", customer, laptop));
        assertEquals(3, rows("SELECT count(*) FROM omnirec.erasure_tombstones WHERE tenant_id = 'tenant-a'"),
                "one tombstone for the customer and one per device");

        // The tombstones stop late events, by user id and by device.
        view("pk_test_del_a", phone, null);
        view("pk_test_del_a", "anon_new_" + suffix, customer);
        identify("pk_test_del_a", laptop, customer);
        assertEquals(0, eventsOf("tenant-a", phone, customer));
        assertEquals(0, eventsOf("tenant-a", "anon_new_" + suffix, customer));
        assertTrue(linkStore.anonymousIdsFor("tenant-a", customer).isEmpty(), "an identify cannot relink");

        // History reads come back empty, and repeating the deletion is harmless.
        String history = mockMvc.perform(get("/v1/customers/" + customer + "/events")
                        .header("Authorization", "Bearer sk_test_del_a_secret"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, objectMapper.readTree(history).path("events").size());
        assertEquals(0, erase("sk_test_del_a_secret", customer).path("eventsDeleted").asLong());
    }

    @Test
    void needsTheTenantsSecretKey() throws Exception {
        mockMvc.perform(delete("/v1/customers/anyone")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/v1/customers/anyone").header("Authorization", "Bearer pk_test_del_a"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/v1/customers/anyone").header("Authorization", "Bearer sk_test_del_a_secret")
                        .header("X-Omnirec-Tenant", "tenant-b"))
                .andExpect(status().isForbidden());
    }
}
