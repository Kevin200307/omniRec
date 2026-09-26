// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.transport;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import io.omnirec.tracker.config.CommerceTrackerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * B1 from the audit: the backend SDK used to log and drop a batch on any
 * failure, so a brief Event API blip lost authoritative purchases.
 */
class HttpEventSenderTest {

    private static final String URL = "https://events.example.com/v1/events/batch";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private CommerceTrackerProperties properties;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new CommerceTrackerProperties();
        properties.setEndpoint("https://events.example.com");
        properties.setApiKey("sk_backend_key");
        properties.setAsync(false);
        properties.setMaxRetries(3);
    }

    private HttpEventSender sender() {
        return new HttpEventSender(restTemplate, properties, sleeps::add);
    }

    private CommerceEvent purchase() {
        return CommerceEvent.builder()
                .eventId("evt:purchase_completed:order_1")
                .eventType(EventType.PURCHASE_COMPLETED)
                .identity(EventIdentity.authenticated("anon_A", "customer_123", "s1"))
                .commerce(CommerceData.builder().orderId("order_1").build())
                .build();
    }

    @Test
    void retriesA503AndThenDelivers() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess());

        HttpEventSender sender = sender();
        sender.send(purchase());

        server.verify();
        assertEquals(0, sender.droppedCount());
        assertEquals(2, sleeps.size());
    }

    @Test
    void retriesANetworkFailure() {
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("connection refused")));
        server.expect(requestTo(URL)).andRespond(withSuccess());

        HttpEventSender sender = sender();
        sender.send(purchase());

        server.verify();
        assertEquals(0, sender.droppedCount());
    }

    @Test
    void retriesA429() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess());

        sender().send(purchase());

        server.verify();
    }

    @Test
    void neverRetriesAPermanentRejection() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        HttpEventSender sender = sender();
        sender.send(purchase());

        server.verify();
        assertEquals(1, sender.droppedCount());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void givesUpAfterMaxRetriesRatherThanRetryingForever() {
        server.expect(ExpectedCount.times(4), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        HttpEventSender sender = sender();
        sender.send(purchase());

        server.verify();
        assertEquals(1, sender.droppedCount(), "the loss is counted, not silent");
    }

    @Test
    void sendsTheApiKeyHeader() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Omnirec-Key", "sk_backend_key"))
                .andRespond(withSuccess());

        sender().send(purchase());

        server.verify();
    }

    @Test
    void backsOffExponentiallyWithACap() {
        properties.setRetryInitialInterval(Duration.ofMillis(500));
        properties.setRetryMaxInterval(Duration.ofSeconds(3));
        HttpEventSender sender = sender();

        assertEquals(Duration.ofMillis(500), sender.backoff(0));
        assertEquals(Duration.ofSeconds(1), sender.backoff(1));
        assertEquals(Duration.ofSeconds(2), sender.backoff(2));
        assertEquals(Duration.ofSeconds(3), sender.backoff(3));
        assertEquals(Duration.ofSeconds(3), sender.backoff(40));
    }
}
