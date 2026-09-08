package io.omnirec.catalog.providers.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.personalize.PersonalizeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeevents.model.Item;
import software.amazon.awssdk.services.personalizeevents.model.PutItemsRequest;
import software.amazon.awssdk.services.personalizeevents.model.PutItemsResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** No live AWS calls — PersonalizeEventsClient is mocked throughout. */
@ExtendWith(MockitoExtension.class)
class PersonalizeCatalogProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private PersonalizeEventsClient eventsClient;

    private PersonalizeProperties properties() {
        PersonalizeProperties props = new PersonalizeProperties();
        props.setItemsDatasetArn("arn:aws:personalize:us-east-1:123:dataset/my-group/ITEMS");
        return props;
    }

    private CatalogItem item(String id) {
        return new CatalogItem(id, "Widget " + id, "A widget", new BigDecimal("9.99"), "widgets", "http://x/" + id + ".png",
                Map.of("color", "red"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void mapsCatalogItemToAPersonalizeItemWithProductIdAsItemId() throws Exception {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, properties());

        Item mapped = provider.toPersonalizeItem(item("sku-1"));

        assertEquals("sku-1", mapped.itemId());
        Map<?, ?> props = MAPPER.readValue(mapped.properties(), Map.class);
        assertEquals("Widget sku-1", props.get("title"));
        assertEquals("widgets", props.get("category"));
        assertEquals("red", props.get("color"), "free-form attributes must pass through");
    }

    @Test
    void upsertItemsSendsToTheConfiguredItemsDatasetArnAndReportsAllAccepted() {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, properties());

        SyncResult result = provider.upsertItems(List.of(item("sku-1"), item("sku-2")));

        ArgumentCaptor<PutItemsRequest> captor = ArgumentCaptor.forClass(PutItemsRequest.class);
        verify(eventsClient).putItems(captor.capture());
        assertEquals("arn:aws:personalize:us-east-1:123:dataset/my-group/ITEMS", captor.getValue().datasetArn());
        assertEquals(2, captor.getValue().items().size());
        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
    }

    @Test
    void upsertItemsOverPersonalizesBatchLimitIsChunkedIntoMultipleCalls() {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, properties());
        List<CatalogItem> items = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            items.add(item("sku-" + i));
        }

        SyncResult result = provider.upsertItems(items);

        // 10 + 10 + 5 — PutItems' documented per-call limit is 10 items.
        verify(eventsClient, times(3)).putItems(any(PutItemsRequest.class));
        assertEquals(25, result.accepted());
    }

    @Test
    void aChunkThatThrowsIsReportedAsRejectedWithoutLosingOtherChunksAcceptedCount() {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, properties());
        List<CatalogItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item("sku-" + i));
        }
        // First putItems call (chunk of 10) fails; second (chunk of 10) succeeds.
        doThrow(new RuntimeException("throttled"))
                .doReturn(PutItemsResponse.builder().build())
                .when(eventsClient).putItems(any(PutItemsRequest.class));

        SyncResult result = provider.upsertItems(items);

        assertEquals(10, result.accepted());
        assertEquals(10, result.rejected());
        assertEquals(1, result.rejections().size());
        assertEquals("*", result.rejections().get(0).productId());
        assertTrue(result.rejections().get(0).reason().contains("throttled"));
    }

    @Test
    void upsertItemsWithoutAnItemsDatasetArnConfiguredRejectsEverythingAndNeverCallsAws() {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, new PersonalizeProperties());

        SyncResult result = provider.upsertItems(List.of(item("sku-1")));

        verifyNoInteractions(eventsClient);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals("*", result.rejections().get(0).productId());
    }

    @Test
    void removeItemsIsANoOpThatNeverCallsAwsAndReportsEverythingRejectedWithWhy() {
        PersonalizeCatalogProvider provider = new PersonalizeCatalogProvider(eventsClient, properties());

        SyncResult result = provider.removeItems(List.of("sku-1"));

        verifyNoInteractions(eventsClient);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().get(0).reason().toLowerCase().contains("no real-time item-deletion api"));
    }
}
