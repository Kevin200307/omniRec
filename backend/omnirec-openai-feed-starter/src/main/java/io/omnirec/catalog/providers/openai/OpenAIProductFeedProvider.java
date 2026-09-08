package io.omnirec.catalog.providers.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.FeedFileProvider;
import io.omnirec.catalog.RejectedItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.openaifeed.OpenAIFeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * No official Java SDK exists for OpenAI's Product Feed — the endpoint is
 * merchant-specific, issued only after OpenAI's own approval process (see
 * OpenAIFeedProperties). This POSTs the whole catalog as one request via
 * the JDK's built-in HttpClient, since a single outbound call doesn't
 * justify adding a dependency.
 *
 * Because there's no public spec to verify per-item response parsing
 * against, a successful HTTP response is trusted to mean every valid item
 * in the request body was accepted — this provider cannot report finer-
 * grained per-item acceptance than that. Items that fail *local* validation
 * (missing title/price/availability) are never included in the request at
 * all, and are always reported precisely, regardless of the HTTP outcome.
 */
public class OpenAIProductFeedProvider implements FeedFileProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProductFeedProvider.class);

    private static final List<String> COLUMN_ORDER = List.of(
            "id", "title", "description", "price", "availability", "brand", "gtin", "condition", "category", "image_url"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAIFeedProperties properties;

    public OpenAIProductFeedProvider(HttpClient httpClient, ObjectMapper objectMapper, OpenAIFeedProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "openai-feed";
    }

    @Override
    public SyncResult generateAndPublish(List<CatalogItem> fullCatalogSnapshot) {
        List<CatalogItem> valid = new ArrayList<>();
        List<RejectedItem> rejections = new ArrayList<>();

        for (CatalogItem item : fullCatalogSnapshot) {
            String reason = validate(item);
            if (reason != null) {
                rejections.add(new RejectedItem(item.productId(), reason));
            } else {
                valid.add(item);
            }
        }

        if (valid.isEmpty()) {
            return new SyncResult(getProviderName(), 0, rejections.size(), rejections);
        }

        String format = properties.getFormat() == null ? "json" : properties.getFormat().toLowerCase();
        String body;
        try {
            body = switch (format) {
                case "json" -> toJson(valid);
                case "csv" -> toDelimitedText(valid, ",");
                case "tsv" -> toDelimitedText(valid, "\t");
                case "xml" -> toXml(valid);
                default -> null;
            };
        } catch (Exception e) {
            return wholeBatchRejection(fullCatalogSnapshot.size(), rejections, "failed to build feed content: " + e.getMessage());
        }
        if (body == null) {
            return wholeBatchRejection(fullCatalogSnapshot.size(), rejections, "unsupported omnirec.providers.openai-feed.format: " + format);
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentTypeFor(format))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new SyncResult(getProviderName(), valid.size(), rejections.size(), rejections);
            }

            log.warn("OpenAI feed endpoint returned {}: {}", response.statusCode(), truncate(response.body()));
            return wholeBatchRejection(fullCatalogSnapshot.size(), rejections,
                    "endpoint returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("OpenAI feed publish failed: {}", e.getMessage());
            return wholeBatchRejection(fullCatalogSnapshot.size(), rejections, "feed publish failed: " + e.getMessage());
        }
    }

    /** Required fields per spec: title, price, availability. Everything else is optional. */
    private String validate(CatalogItem item) {
        if (item.title() == null || item.title().isBlank()) return "missing title";
        if (item.price() == null) return "missing price";
        if (item.availability() == null || item.availability().isBlank()) return "missing availability";
        return null;
    }

    private SyncResult wholeBatchRejection(int fullBatchSize, List<RejectedItem> priorRejections, String reason) {
        List<RejectedItem> all = new ArrayList<>(priorRejections);
        all.add(new RejectedItem("*", reason));
        return new SyncResult(getProviderName(), 0, fullBatchSize, all);
    }

    private String contentTypeFor(String format) {
        return switch (format) {
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    private String toJson(List<CatalogItem> items) throws JsonProcessingException {
        List<Map<String, Object>> records = items.stream().map(this::toRecord).toList();
        return objectMapper.writeValueAsString(records);
    }

    private String toDelimitedText(List<CatalogItem> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(delimiter, COLUMN_ORDER)).append('\n');
        for (CatalogItem item : items) {
            Map<String, Object> record = toRecord(item);
            for (int i = 0; i < COLUMN_ORDER.size(); i++) {
                if (i > 0) sb.append(delimiter);
                Object value = record.get(COLUMN_ORDER.get(i));
                sb.append(sanitize(value == null ? "" : value.toString(), delimiter));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String toXml(List<CatalogItem> items) {
        StringBuilder sb = new StringBuilder("<products>\n");
        for (CatalogItem item : items) {
            sb.append("  <product>\n");
            toRecord(item).forEach((key, value) -> {
                if (value != null) {
                    sb.append("    <").append(key).append(">")
                            .append(escapeXml(value.toString()))
                            .append("</").append(key).append(">\n");
                }
            });
            sb.append("  </product>\n");
        }
        sb.append("</products>\n");
        return sb.toString();
    }

    private Map<String, Object> toRecord(CatalogItem item) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", item.productId());
        record.put("title", item.title());
        record.put("description", item.description());
        record.put("price", item.price());
        record.put("availability", item.availability());
        record.put("brand", item.brand());
        record.put("gtin", item.gtin());
        record.put("condition", item.condition());
        record.put("category", item.category());
        record.put("image_url", item.imageUrl());
        return record;
    }

    private String sanitize(String value, String delimiter) {
        return value.replace(delimiter, " ").replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
