package io.omnirec.catalog.providers.googlemerchant;

import com.google.api.services.content.ShoppingContent;
import com.google.api.services.content.model.Product;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.googlemerchant.GoogleMerchantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** No live Google calls — ShoppingContent's fluent client is mocked throughout. */
@ExtendWith(MockitoExtension.class)
class GoogleMerchantCatalogProviderTest {

    @Mock
    private ShoppingContent client;
    @Mock
    private ShoppingContent.Products products;
    @Mock
    private ShoppingContent.Products.Insert insertRequest;
    @Mock
    private ShoppingContent.Products.Delete deleteRequest;

    private GoogleMerchantProperties properties() {
        GoogleMerchantProperties props = new GoogleMerchantProperties();
        props.setMerchantId("12345");
        props.setProductUrlTemplate("https://mystore.com/products/{productId}");
        return props;
    }

    private CatalogItem validItem(String id) {
        return new CatalogItem(id, "Widget", "A widget", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), "0012345678905", "Acme", "new", "in_stock", null);
    }

    @Test
    void validItemsAreInsertedAndCountedAsAccepted() throws IOException {
        when(client.products()).thenReturn(products);
        when(products.insert(any(), any(Product.class))).thenReturn(insertRequest);
        when(insertRequest.execute()).thenReturn(new Product());
        GoogleMerchantCatalogProvider provider = new GoogleMerchantCatalogProvider(client, new GoogleMerchantItemMapper(), properties());

        SyncResult result = provider.upsertItems(List.of(validItem("sku-1")));

        verify(products).insert(any(), any(Product.class));
        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
    }

    @Test
    void itemsThatFailMapperValidationAreNeverSentToTheApi() {
        GoogleMerchantCatalogProvider provider = new GoogleMerchantCatalogProvider(client, new GoogleMerchantItemMapper(), properties());
        CatalogItem missingGtin = new CatalogItem("sku-2", "Widget", "desc", new BigDecimal("9.99"), "widgets", "http://x/w.png",
                Map.of(), Instant.now(), null, "Acme", "new", "in_stock", null);

        SyncResult result = provider.upsertItems(List.of(missingGtin));

        verifyNoInteractions(client);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().get(0).reason().toLowerCase().contains("gtin"));
    }

    @Test
    void aContentApiFailureForOneItemDoesNotStopOthers() throws IOException {
        when(client.products()).thenReturn(products);
        when(products.insert(any(), any(Product.class))).thenReturn(insertRequest);
        when(insertRequest.execute())
                .thenThrow(new IOException("throttled"))
                .thenReturn(new Product());
        GoogleMerchantCatalogProvider provider = new GoogleMerchantCatalogProvider(client, new GoogleMerchantItemMapper(), properties());

        SyncResult result = provider.upsertItems(List.of(validItem("sku-1"), validItem("sku-2")));

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals("sku-1", result.rejections().get(0).productId());
    }

    @Test
    void removeItemsDeletesUsingTheCompositeRestProductId() throws IOException {
        when(client.products()).thenReturn(products);
        when(products.delete(any(), eq("online:en:US:sku-1"))).thenReturn(deleteRequest);
        doNothing().when(deleteRequest).execute();
        GoogleMerchantCatalogProvider provider = new GoogleMerchantCatalogProvider(client, new GoogleMerchantItemMapper(), properties());

        SyncResult result = provider.removeItems(List.of("sku-1"));

        verify(products).delete(any(), eq("online:en:US:sku-1"));
        assertEquals(1, result.accepted());
    }
}
