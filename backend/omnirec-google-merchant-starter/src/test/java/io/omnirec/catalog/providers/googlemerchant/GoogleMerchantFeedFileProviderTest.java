package io.omnirec.catalog.providers.googlemerchant;

import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.googlemerchant.GoogleMerchantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleMerchantFeedFileProviderTest {

    @TempDir
    Path tempDir;

    private GoogleMerchantProperties properties(String format) {
        GoogleMerchantProperties props = new GoogleMerchantProperties();
        props.setProductUrlTemplate("https://mystore.com/products/{productId}");
        props.setFeedFormat(format);
        props.setFeedOutputPath(tempDir.resolve("feed." + format).toString());
        return props;
    }

    private CatalogItem validItem(String id) {
        return new CatalogItem(id, "Widget", "A widget", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null);
    }

    @Test
    void writesATsvFeedWithAllValidItems() throws IOException {
        GoogleMerchantProperties props = properties("tsv");
        GoogleMerchantFeedFileProvider provider = new GoogleMerchantFeedFileProvider(new GoogleMerchantItemMapper(), props);

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1"), validItem("sku-2")));

        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
        String content = Files.readString(Path.of(props.getFeedOutputPath()));
        String[] lines = content.split("\n");
        assertEquals(3, lines.length, "header + 2 rows");
        assertTrue(lines[0].startsWith("id\ttitle"));
        assertTrue(lines[1].contains("sku-1"));
    }

    @Test
    void writesACsvFeedWhenConfigured() throws IOException {
        GoogleMerchantProperties props = properties("csv");
        GoogleMerchantFeedFileProvider provider = new GoogleMerchantFeedFileProvider(new GoogleMerchantItemMapper(), props);

        provider.generateAndPublish(List.of(validItem("sku-1")));

        String content = Files.readString(Path.of(props.getFeedOutputPath()));
        assertTrue(content.contains("id,title"));
    }

    @Test
    void rejectedItemsAreExcludedFromTheFileButReportedInTheResult() throws IOException {
        GoogleMerchantProperties props = properties("tsv");
        CatalogItem missingGtin = new CatalogItem("sku-bad", "Widget", "desc", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, "Acme", "new", "in_stock", null);
        GoogleMerchantFeedFileProvider provider = new GoogleMerchantFeedFileProvider(new GoogleMerchantItemMapper(), props);

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1"), missingGtin));

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        String content = Files.readString(Path.of(props.getFeedOutputPath()));
        assertFalse(content.contains("sku-bad"));
    }

    @Test
    void unsupportedFormatRejectsEverythingWithoutWritingAFile() {
        GoogleMerchantProperties props = properties("xml");
        GoogleMerchantFeedFileProvider provider = new GoogleMerchantFeedFileProvider(new GoogleMerchantItemMapper(), props);

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1")));

        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertFalse(Files.exists(Path.of(props.getFeedOutputPath())));
    }

    @Test
    void missingOutputPathRejectsEverythingWithAClearReason() {
        GoogleMerchantProperties props = properties("tsv");
        props.setFeedOutputPath(null);
        GoogleMerchantFeedFileProvider provider = new GoogleMerchantFeedFileProvider(new GoogleMerchantItemMapper(), props);

        SyncResult result = provider.generateAndPublish(List.of(validItem("sku-1")));

        assertEquals(1, result.rejected());
        assertTrue(result.rejections().get(0).reason().contains("feed-output-path"));
    }
}
