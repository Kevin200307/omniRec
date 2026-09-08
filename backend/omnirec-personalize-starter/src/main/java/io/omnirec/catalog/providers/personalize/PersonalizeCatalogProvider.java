package io.omnirec.catalog.providers.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.CatalogProvider;
import io.omnirec.personalize.PersonalizeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeevents.model.Item;
import software.amazon.awssdk.services.personalizeevents.model.PutItemsRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog sync counterpart to AmazonPersonalizeProvider — same
 * PersonalizeEventsClient bean (already configured by
 * PersonalizeAutoConfiguration with the developer's own credentials), just
 * a different method on it. Registered as a CatalogProvider bean, dispatched
 * to by CatalogSyncServiceImpl exactly like AlgoliaCatalogProvider — the
 * calling application never distinguishes between them.
 */
public class PersonalizeCatalogProvider implements CatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(PersonalizeCatalogProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AWS Personalize's PutItems accepts at most 10 items per call (same
     * limit as PutEvents' eventList) — verify against current AWS
     * Personalize service quotas before relying on this in production, as
     * AWS can and does revise per-API limits.
     */
    private static final int PUT_ITEMS_BATCH_SIZE = 10;

    private final PersonalizeEventsClient eventsClient;
    private final PersonalizeProperties properties;

    public PersonalizeCatalogProvider(PersonalizeEventsClient eventsClient, PersonalizeProperties properties) {
        this.eventsClient = eventsClient;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "aws-personalize";
    }

    @Override
    public void upsertItems(List<CatalogItem> items) {
        if (properties.getItemsDatasetArn() == null) {
            log.warn("omnirec.recommendation.aws-personalize.items-dataset-arn is not set — skipping catalog sync");
            return;
        }
        for (List<CatalogItem> chunk : partition(items, PUT_ITEMS_BATCH_SIZE)) {
            List<Item> mapped = chunk.stream().map(this::toPersonalizeItem).toList();
            eventsClient.putItems(PutItemsRequest.builder()
                    .datasetArn(properties.getItemsDatasetArn())
                    .items(mapped)
                    .build());
        }
    }

    /**
     * AWS Personalize has no real-time item-deletion API (PersonalizeEventsClient
     * exposes putItems/putUsers/putEvents/putActions/putActionInteractions —
     * nothing that removes a record). The correct pattern here is to exclude
     * removed items at query time via a Personalize Filter keyed on an item
     * attribute you maintain yourself (e.g. upsert items with an
     * "available": false attribute via sync(), then filter recommendations
     * on it) rather than to fabricate a delete call this API doesn't support.
     * This logs and no-ops rather than silently pretending removal happened.
     */
    @Override
    public void removeItems(List<String> productIds) {
        log.warn(
                "PersonalizeCatalogProvider.removeItems is a no-op: AWS Personalize has no real-time item-deletion API. " +
                        "Exclude these {} item(s) via a Personalize Filter on an item attribute instead (e.g. re-sync them " +
                        "with an \"available\": false attribute and filter on it in your campaign/recommender).",
                productIds.size()
        );
    }

    /**
     * Pure CatalogItem → Personalize Item mapping, kept separate from the
     * network call so it's unit-testable without a live AWS client — same
     * pattern as AmazonPersonalizeProvider.toPersonalizeEvent.
     */
    public Item toPersonalizeItem(CatalogItem item) {
        Map<String, Object> properties = new LinkedHashMap<>(item.attributes());
        properties.put("title", item.title());
        properties.put("description", item.description());
        properties.put("price", item.price());
        properties.put("category", item.category());
        properties.put("imageUrl", item.imageUrl());

        try {
            return Item.builder()
                    .itemId(item.productId())
                    .properties(MAPPER.writeValueAsString(properties))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize CatalogItem " + item.productId() + " for Personalize", e);
        }
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }
}
