package io.omnirec.catalog.providers.openai;

import io.omnirec.catalog.diagnostics.DiagnosticResult;
import io.omnirec.catalog.diagnostics.FeedDiagnostic;
import io.omnirec.openaifeed.OpenAIFeedProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reachability-only check — there is no published health or auth-challenge
 * spec for OpenAI's merchant-specific feed endpoints to verify against
 * (each one is issued individually after approval), so this confirms the
 * endpoint responds at all — any HTTP status, not a connection failure —
 * rather than attempting a real feed publish. A 2xx/4xx response both count
 * as "reachable"; only a connection-level failure (DNS, refused, timeout)
 * counts as unhealthy.
 */
public class OpenAIFeedDiagnostic implements FeedDiagnostic {

    private final HttpClient httpClient;
    private final OpenAIFeedProperties properties;

    public OpenAIFeedDiagnostic(HttpClient httpClient, OpenAIFeedProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "openai-feed";
    }

    @Override
    public DiagnosticResult check() {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            return DiagnosticResult.unhealthy(
                    "omnirec.providers.openai-feed.endpoint is not configured",
                    "https://platform.openai.com/docs"
            );
        }
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<Void> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
            return DiagnosticResult.healthy(
                    "Endpoint reachable, responded HTTP " + response.statusCode()
                            + " — this confirms connectivity only, not that your merchant account is approved for feed publishing."
            );
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return DiagnosticResult.unhealthy("Endpoint unreachable: " + reason, "https://platform.openai.com/docs");
        }
    }
}
