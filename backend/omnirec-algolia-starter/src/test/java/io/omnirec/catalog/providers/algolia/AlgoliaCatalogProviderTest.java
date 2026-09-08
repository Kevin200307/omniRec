package io.omnirec.catalog.providers.algolia;

import com.algolia.api.SearchClient;
import io.omnirec.algolia.AlgoliaProperties;
import io.omnirec.catalog.CatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** No live Algolia calls — SearchClient is mocked throughout. */
@ExtendWith(MockitoExtension.class)
class AlgoliaCatalogProviderTest {

    @Mock
    private SearchClient client;

    private AlgoliaProperties properties() {
        AlgoliaProperties props = new AlgoliaProperties();
        props.setIndexName("products");
        return props;
    }

    private CatalogItem item(String id) {
        return new CatalogItem(id, "Widget " + id, "A widget", new BigDecimal("9.99"), "widgets", "http://x/" + id + ".png",
                Map.of("color", "red"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void mapsCatalogItemToAnAlgoliaRecordWithProductIdAsObjectID() {
        AlgoliaCatalogProvider provider = new AlgoliaCatalogProvider(client, properties());

        Map<String, Object> record = provider.toAlgoliaRecord(item("sku-1"));

        assertEquals("sku-1", record.get("objectID"));
        assertEquals("Widget sku-1", record.get("title"));
        assertEquals("widgets", record.get("category"));
        assertEquals("red", record.get("color"), "free-form attributes must pass through");
        assertEquals(new BigDecimal("9.99"), record.get("price"));
    }

    @Test
    void upsertItemsSendsToTheConfiguredIndex() {
        AlgoliaCatalogProvider provider = new AlgoliaCatalogProvider(client, properties());

        provider.upsertItems(List.of(item("sku-1"), item("sku-2")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).saveObjects(eq("products"), captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void upsertItemsOverAlgoliasBatchLimitIsChunkedIntoMultipleCalls() {
        AlgoliaCatalogProvider provider = new AlgoliaCatalogProvider(client, properties());
        List<CatalogItem> items = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            items.add(item("sku-" + i));
        }

        provider.upsertItems(items);

        // 1000 + 500 — Algolia's documented per-batch limit is 1000 records.
        verify(client, times(2)).saveObjects(eq("products"), anyList());
    }

    @Test
    void removeItemsDeletesByObjectIdFromTheConfiguredIndex() {
        AlgoliaCatalogProvider provider = new AlgoliaCatalogProvider(client, properties());

        provider.removeItems(List.of("sku-1", "sku-2"));

        verify(client).deleteObjects("products", List.of("sku-1", "sku-2"));
    }
}
