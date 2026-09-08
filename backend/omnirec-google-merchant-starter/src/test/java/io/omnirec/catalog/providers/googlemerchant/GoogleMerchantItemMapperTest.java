package io.omnirec.catalog.providers.googlemerchant;

import io.omnirec.catalog.CatalogItem;
import io.omnirec.googlemerchant.GoogleMerchantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure mapping/validation logic — no ShoppingContent client, no file I/O. */
class GoogleMerchantItemMapperTest {

    private final GoogleMerchantItemMapper mapper = new GoogleMerchantItemMapper();

    private GoogleMerchantProperties properties() {
        GoogleMerchantProperties props = new GoogleMerchantProperties();
        props.setProductUrlTemplate("https://mystore.com/products/{productId}");
        props.setContentLanguage("en");
        props.setTargetCountry("US");
        props.setChannel("online");
        props.setCurrency("USD");
        return props;
    }

    private CatalogItem fullyValidItem() {
        return new CatalogItem(
                "sku-1", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", new BigDecimal("1.5")
        );
    }

    @Test
    void aFullyValidItemMapsSuccessfully() {
        MappingOutcome outcome = mapper.map(fullyValidItem(), properties());

        assertInstanceOf(MappingOutcome.Mapped.class, outcome);
        Map<String, String> attrs = ((MappingOutcome.Mapped) outcome).attributes();
        assertEquals("sku-1", attrs.get("id"));
        assertEquals("Widget", attrs.get("title"));
        assertEquals("https://mystore.com/products/sku-1", attrs.get("link"));
        assertEquals("in_stock", attrs.get("availability"));
        assertEquals("0012345678905", attrs.get("gtin"));
        assertEquals("19.99 USD", attrs.get("price"));
        assertEquals("Acme", attrs.get("brand"));
        assertEquals("1.5 kg", attrs.get("shipping_weight"));
    }

    @Test
    void anItemMissingGtinIsRejectedBeforeAnyApiOrFileCallWouldBeMade() {
        CatalogItem item = new CatalogItem(
                "sku-2", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, "Acme", "new", "in_stock", null
        );

        MappingOutcome outcome = mapper.map(item, properties());

        assertInstanceOf(MappingOutcome.Rejected.class, outcome);
        MappingOutcome.Rejected rejected = (MappingOutcome.Rejected) outcome;
        assertEquals("sku-2", rejected.productId());
        assertTrue(rejected.reason().toLowerCase().contains("gtin"));
    }

    @Test
    void anItemWithBlankGtinIsAlsoRejected() {
        CatalogItem item = new CatalogItem(
                "sku-3", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "   ", "Acme", "new", "in_stock", null
        );

        MappingOutcome outcome = mapper.map(item, properties());

        assertInstanceOf(MappingOutcome.Rejected.class, outcome);
    }

    @Test
    void anItemWithAnInvalidAvailabilityValueIsRejectedWithTheAllowedValuesInTheReason() {
        CatalogItem item = new CatalogItem(
                "sku-4", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "discontinued", null
        );

        MappingOutcome outcome = mapper.map(item, properties());

        assertInstanceOf(MappingOutcome.Rejected.class, outcome);
        MappingOutcome.Rejected rejected = (MappingOutcome.Rejected) outcome;
        assertTrue(rejected.reason().contains("in_stock"));
        assertTrue(rejected.reason().contains("discontinued"));
    }

    @Test
    void anItemMissingTitleIsRejected() {
        CatalogItem item = new CatalogItem(
                "sku-5", null, "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null
        );

        assertInstanceOf(MappingOutcome.Rejected.class, mapper.map(item, properties()));
    }

    @Test
    void anItemMissingPriceIsRejected() {
        CatalogItem item = new CatalogItem(
                "sku-6", "Widget", "A widget", null, "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null
        );

        assertInstanceOf(MappingOutcome.Rejected.class, mapper.map(item, properties()));
    }

    @Test
    void withoutAProductUrlTemplateConfiguredAndNoPerItemUrlEveryOtherwiseValidItemIsRejectedWithAConfigErrorReason() {
        GoogleMerchantProperties propsWithoutTemplate = properties();
        propsWithoutTemplate.setProductUrlTemplate(null);

        MappingOutcome outcome = mapper.map(fullyValidItem(), propsWithoutTemplate);

        assertInstanceOf(MappingOutcome.Rejected.class, outcome);
        MappingOutcome.Rejected rejected = (MappingOutcome.Rejected) outcome;
        assertTrue(rejected.reason().contains("product-url-template"));
    }

    @Test
    void aPerItemProductUrlIsUsedWhenNoStoreTemplateIsConfigured() {
        GoogleMerchantProperties propsWithoutTemplate = properties();
        propsWithoutTemplate.setProductUrlTemplate(null);
        CatalogItem item = new CatalogItem(
                "sku-7", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null,
                "https://mystore.com/custom/path/sku-7", null
        );

        MappingOutcome outcome = mapper.map(item, propsWithoutTemplate);

        assertInstanceOf(MappingOutcome.Mapped.class, outcome);
        assertEquals("https://mystore.com/custom/path/sku-7", ((MappingOutcome.Mapped) outcome).attributes().get("link"));
    }

    @Test
    void aPerItemProductUrlTakesPriorityOverTheStoreTemplateWhenBothArePresent() {
        CatalogItem item = new CatalogItem(
                "sku-8", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null,
                "https://mystore.com/override/sku-8", null
        );

        MappingOutcome outcome = mapper.map(item, properties());

        assertInstanceOf(MappingOutcome.Mapped.class, outcome);
        assertEquals("https://mystore.com/override/sku-8", ((MappingOutcome.Mapped) outcome).attributes().get("link"));
    }

    @Test
    void aPerItemCurrencyTakesPriorityOverTheStoreDefault() {
        CatalogItem item = new CatalogItem(
                "sku-9", "Widget", "A widget", new BigDecimal("19.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null,
                null, "EUR"
        );

        MappingOutcome outcome = mapper.map(item, properties());

        assertInstanceOf(MappingOutcome.Mapped.class, outcome);
        assertEquals("19.99 EUR", ((MappingOutcome.Mapped) outcome).attributes().get("price"));
    }

    @Test
    void withoutAPerItemCurrencyTheStoreDefaultIsUsed() {
        MappingOutcome outcome = mapper.map(fullyValidItem(), properties());

        assertEquals("19.99 USD", ((MappingOutcome.Mapped) outcome).attributes().get("price"));
    }

    @Test
    void restProductIdIsTheChannelLanguageCountryOfferIdComposite() {
        String restId = GoogleMerchantItemMapper.toRestProductId("sku-1", properties());

        assertEquals("online:en:US:sku-1", restId);
    }
}
