package io.omnirec.catalog.providers.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.openaifeed.OpenAIFeedProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real local HTTP server (com.sun.net.httpserver, part of the JDK) rather
 * than mocking java.net.http.HttpClient — exercises the actual request
 * building/response handling instead of a fragile mock of a tricky JDK type.
 */
class OpenAIProductFeedProviderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/feed", exchange -> {
            requestCount.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAIFeedProperties properties(String format) {
        OpenAIFeedProperties props = new OpenAIFeedProperties();
        props.setEndpoint("http://localhost:" + port + "/feed");
        props.setFormat(format);
        return props;
    }

    private CatalogItem validItem(String id) {
        return new CatalogItem(id, "Widget", "A widget", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, null, null, "in_stock", null);
    }

    private OpenAIProductFeedProvider provider(OpenAIFeedProperties props) {
        return new OpenAIProductFeedProvider(HttpClient.newHttpClient(), new ObjectMapper(), props);
    }

    @Test
    void postsValidItemsAsJsonByDefaultAndReportsAccepted() {
        OpenAIProductFeedProvider provider = provider(properties("json"));

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1")));

        assertEquals(1, requestCount.get());
        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
        assertTrue(lastRequestBody.get().contains("sku-1"));
    }

    @Test
    void itemsMissingRequiredFieldsAreExcludedAndNeverSentOverTheWire() {
        OpenAIProductFeedProvider provider = provider(properties("json"));
        CatalogItem missingPrice = new CatalogItem("sku-bad", "Widget", "desc", null, "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, null, null, "in_stock", null);

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1"), missingPrice));

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertFalse(lastRequestBody.get().contains("sku-bad"));
    }

    @Test
    void sendsBearerAuthWhenApiKeyIsConfigured() {
        OpenAIFeedProperties props = properties("json");
        props.setApiKey("secret-token");
        OpenAIProductFeedProvider provider = provider(props);

        provider.generateAndPublish(List.of(validItem("sku-1")));

        assertEquals("Bearer secret-token", lastAuthHeader.get());
    }

    @Test
    void aNonSuccessResponseRejectsTheWholeBatchNotJustTheInvalidItems() {
        responseStatus = 500;
        OpenAIProductFeedProvider provider = provider(properties("json"));

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1"), validItem("sku-2")));

        assertEquals(0, result.accepted());
        assertEquals(2, result.rejected());
        assertTrue(result.rejections().stream().anyMatch(r -> r.productId().equals("*")));
    }

    @Test
    void whenEveryItemFailsLocalValidationNoNetworkCallIsMadeAtAll() {
        OpenAIProductFeedProvider provider = provider(properties("json"));
        CatalogItem missingTitle = new CatalogItem("sku-bad", null, "desc", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, null, null, "in_stock", null);

        SyncResult result = provider.generateAndPublish(List.of(missingTitle));

        assertEquals(0, requestCount.get());
        assertEquals(1, result.rejected());
    }

    @Test
    void csvFormatPostsDelimitedTextWithAHeaderRow() {
        OpenAIProductFeedProvider provider = provider(properties("csv"));

        provider.generateAndPublish(List.of(validItem("sku-1")));

        assertTrue(lastRequestBody.get().startsWith("id,title"));
    }
}
