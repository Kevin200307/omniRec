package io.omnirec.eventapi.app;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventapi.queue.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The request controls from the audit (A1, A2, A4, A5), each of which was
 * documented but not enforced before.
 */
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.tenants.retired-store.api-key=pk_test_retired",
        "omnirec.events.tenants.retired-store.enabled=false",
        "omnirec.events.tenants.limited-store.api-key=pk_test_limited",
        "omnirec.events.max-payload-bytes=4096",
        "omnirec.events.rate-limit.enabled=true",
        "omnirec.events.rate-limit.requests-per-window=3",
        "omnirec.events.rate-limit.window=PT1M",
        "omnirec.events.cors.allowed-origins=https://shop.example",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityTest {

    /** A publisher that can be made to fail, to exercise the broker-down path. */
    static class SwitchablePublisher implements EventPublisher {
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public void publish(CommerceEvent event) {
            if (down.get()) throw new IllegalStateException("broker unreachable");
        }
    }

    @TestConfiguration
    static class Publisher {
        @Bean
        @Primary
        SwitchablePublisher switchablePublisher() {
            return new SwitchablePublisher();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SwitchablePublisher publisher;

    private String body(String eventId) {
        return """
                {"events":[{"eventId":"%s","eventType":"page_viewed","schemaVersion":"1.0",
                "timestamp":"%s","identity":{"anonymousId":"anon_A","sessionId":"s1"},"context":{},"commerce":{}}]}
                """.formatted(eventId, Instant.now());
    }

    private ResultActions post(String apiKey, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON).content(body);
        if (apiKey != null) request = request.header("X-Omnirec-Key", apiKey);
        return mockMvc.perform(request);
    }

    @Test
    void anOversizedPayloadIsRefusedBeforeItIsParsed() throws Exception {
        String huge = "{\"events\":[],\"padding\":\"" + "x".repeat(8000) + "\"}";

        post("pk_test_demo_store", huge)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void aDisabledTenantsKeyIsRejected() throws Exception {
        post("pk_test_retired", body("evt_" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid or missing API key"));
    }

    /** Checked before parsing: an anonymous caller can't make the server parse a body. */
    @Test
    void aMissingKeyIsRejectedEvenWithAnUnparseableBody() throws Exception {
        post(null, "{not json at all").andExpect(status().isUnauthorized());
    }

    /**
     * Rejections carry CORS headers for allowed origins. Without them the
     * browser reports an opaque network error, the SDK treats it as retryable,
     * and it would retry a bad key forever.
     */
    @Test
    void aRejectionCarriesCorsHeadersSoTheBrowserSdkCanSeeTheStatus() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/events/batch")
                        .header("Origin", "https://shop.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("evt_1")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.example"));
    }

    @Test
    void aMalformedBodyGetsAJson400ThatDoesNotEchoTheInput() throws Exception {
        post("pk_test_demo_store", "{\"events\": [ 4111111111111111 ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("4111")));
    }

    @Test
    void aBrokerOutageIsA503WithRetryAfterSoClientsRetry() throws Exception {
        publisher.down.set(true);
        try {
            post("pk_test_demo_store", body("evt_" + UUID.randomUUID()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.status").value(503));
        } finally {
            publisher.down.set(false);
        }
    }

    /**
     * The limit is keyed on the connection address. It used to key on the
     * left-most X-Forwarded-For, which the client writes itself, so rotating a
     * fake header bypassed the limit entirely.
     */
    @Test
    void theRateLimitCannotBeBypassedByRotatingXForwardedFor() throws Exception {
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            int status = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/v1/events/batch")
                            .header("X-Omnirec-Key", "pk_test_limited")
                            .header("X-Forwarded-For", "10.0.0." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("evt_" + UUID.randomUUID())))
                    .andReturn().getResponse().getStatus();
            if (status == 202) accepted++;
            if (status == 429) limited++;
        }

        assertEquals(3, accepted);
        assertEquals(3, limited);
    }
}
