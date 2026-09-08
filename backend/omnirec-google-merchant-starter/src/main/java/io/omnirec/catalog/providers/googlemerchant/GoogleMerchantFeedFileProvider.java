package io.omnirec.catalog.providers.googlemerchant;

import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.FeedFileProvider;
import io.omnirec.catalog.RejectedItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.googlemerchant.GoogleMerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduled-fetch mode: writes the full catalog as a delimited feed file to
 * a developer-configured local path, for Google to poll — driven
 * exclusively by ScheduledFeedPublisher, never by CatalogSyncService.sync().
 * Shares GoogleMerchantItemMapper with the push-mode provider — see that
 * class for why validation must be identical between the two modes.
 */
public class GoogleMerchantFeedFileProvider implements FeedFileProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleMerchantFeedFileProvider.class);

    private static final List<String> COLUMN_ORDER = List.of(
            "id", "title", "description", "link", "image_link", "availability", "price",
            "gtin", "brand", "condition", "shipping_weight", "content_language", "target_country", "channel"
    );

    private final GoogleMerchantItemMapper mapper;
    private final GoogleMerchantProperties properties;

    public GoogleMerchantFeedFileProvider(GoogleMerchantItemMapper mapper, GoogleMerchantProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "google-merchant";
    }

    @Override
    public SyncResult generateAndPublish(List<CatalogItem> fullCatalogSnapshot) {
        String format = properties.getFeedFormat() == null ? "tsv" : properties.getFeedFormat().toLowerCase();
        if (!format.equals("tsv") && !format.equals("csv")) {
            return wholeBatchRejection(fullCatalogSnapshot.size(),
                    "feed-format '" + format + "' is not implemented for Google Merchant — use tsv or csv; Google's own feed spec is delimited text, not xml/json");
        }
        if (properties.getFeedOutputPath() == null || properties.getFeedOutputPath().isBlank()) {
            return wholeBatchRejection(fullCatalogSnapshot.size(), "omnirec.providers.google-merchant.feed-output-path is not configured");
        }

        String delimiter = format.equals("tsv") ? "\t" : ",";
        List<RejectedItem> rejections = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        for (CatalogItem item : fullCatalogSnapshot) {
            MappingOutcome outcome = mapper.map(item, properties);
            if (outcome instanceof MappingOutcome.Rejected rejected) {
                rejections.add(new RejectedItem(rejected.productId(), rejected.reason()));
            } else {
                rows.add(((MappingOutcome.Mapped) outcome).attributes());
            }
        }

        try {
            Files.writeString(Path.of(properties.getFeedOutputPath()), toDelimitedText(rows, delimiter));
        } catch (IOException e) {
            log.error("Failed to write Google Merchant feed file to {}: {}", properties.getFeedOutputPath(), e.getMessage());
            return wholeBatchRejection(fullCatalogSnapshot.size(), "failed to write feed file: " + e.getMessage());
        }

        return new SyncResult(getProviderName(), rows.size(), rejections.size(), rejections);
    }

    private SyncResult wholeBatchRejection(int batchSize, String reason) {
        return new SyncResult(getProviderName(), 0, batchSize, List.of(new RejectedItem("*", reason)));
    }

    private String toDelimitedText(List<Map<String, String>> rows, String delimiter) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(delimiter, COLUMN_ORDER)).append('\n');
        for (Map<String, String> row : rows) {
            for (int i = 0; i < COLUMN_ORDER.size(); i++) {
                if (i > 0) sb.append(delimiter);
                sb.append(sanitize(row.getOrDefault(COLUMN_ORDER.get(i), ""), delimiter));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Strips whatever would corrupt this delimited format — a stray delimiter, tab, or newline inside a title/description must never shift columns. */
    private String sanitize(String value, String delimiter) {
        return value.replace(delimiter, " ").replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }
}
