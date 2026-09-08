package io.omnirec.catalog.providers.googlemerchant;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.content.ShoppingContent;
import com.google.api.services.content.model.Price;
import com.google.api.services.content.model.Product;
import com.google.api.services.content.model.ProductShippingWeight;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.CatalogProvider;
import io.omnirec.catalog.RejectedItem;
import io.omnirec.catalog.SyncResult;
import io.omnirec.googlemerchant.GoogleMerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Push mode: one Content API call per item. Deliberately does no internal
 * chunking/batching — CatalogSyncServiceImpl calls this once per sync(),
 * which is a reaction to a handful of edited products, not the whole
 * catalog. Bulk/whole-catalog distribution is what scheduled-fetch mode
 * (GoogleMerchantFeedFileProvider) is for; see FeedFileProvider's javadoc.
 *
 * Content API's products.insert acts as an upsert, keyed by
 * (offerId, contentLanguage, targetCountry, channel) — no separate update
 * call exists or is needed.
 */
public class GoogleMerchantCatalogProvider implements CatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleMerchantCatalogProvider.class);

    private final ShoppingContent client;
    private final BigInteger merchantId;
    private final GoogleMerchantItemMapper mapper;
    private final GoogleMerchantProperties properties;

    public GoogleMerchantCatalogProvider(ShoppingContent client, GoogleMerchantItemMapper mapper, GoogleMerchantProperties properties) {
        this.client = client;
        this.merchantId = new BigInteger(properties.getMerchantId());
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "google-merchant";
    }

    @Override
    public SyncResult upsertItems(List<CatalogItem> items) {
        int accepted = 0;
        List<RejectedItem> rejections = new ArrayList<>();

        for (CatalogItem item : items) {
            MappingOutcome outcome = mapper.map(item, properties);
            if (outcome instanceof MappingOutcome.Rejected rejected) {
                rejections.add(new RejectedItem(rejected.productId(), rejected.reason()));
                continue;
            }

            Map<String, String> attrs = ((MappingOutcome.Mapped) outcome).attributes();
            try {
                client.products().insert(merchantId, toProduct(attrs)).execute();
                accepted++;
            } catch (GoogleJsonResponseException e) {
                String reason = reasonFor(e);
                log.warn("Content API rejected item {}: {}", item.productId(), reason);
                rejections.add(new RejectedItem(item.productId(), reason));
            } catch (Exception e) {
                log.warn("Content API insert failed for item {}: {}", item.productId(), e.getMessage());
                rejections.add(new RejectedItem(item.productId(), reasonFor(e)));
            }
        }

        return new SyncResult(getProviderName(), accepted, rejections.size(), rejections);
    }

    @Override
    public SyncResult removeItems(List<String> productIds) {
        int accepted = 0;
        List<RejectedItem> rejections = new ArrayList<>();

        for (String productId : productIds) {
            try {
                client.products().delete(merchantId, GoogleMerchantItemMapper.toRestProductId(productId, properties)).execute();
                accepted++;
            } catch (GoogleJsonResponseException e) {
                rejections.add(new RejectedItem(productId, reasonFor(e)));
            } catch (Exception e) {
                rejections.add(new RejectedItem(productId, reasonFor(e)));
            }
        }

        return new SyncResult(getProviderName(), accepted, rejections.size(), rejections);
    }

    /** GoogleJsonResponseException's details message and plain exceptions' getMessage() can both be null (e.g. UnknownHostException) — falls back to toString() so a rejection reason is never the literal string "null". */
    private String reasonFor(Exception e) {
        if (e instanceof GoogleJsonResponseException jsonException && jsonException.getDetails() != null) {
            String detailMessage = jsonException.getDetails().getMessage();
            if (detailMessage != null) {
                return detailMessage;
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private Product toProduct(Map<String, String> attrs) {
        Product product = new Product();
        product.setOfferId(attrs.get("id"));
        product.setTitle(attrs.get("title"));
        if (attrs.containsKey("description")) {
            product.setDescription(attrs.get("description"));
        }
        product.setLink(attrs.get("link"));
        if (attrs.containsKey("image_link")) {
            product.setImageLink(attrs.get("image_link"));
        }
        product.setAvailability(attrs.get("availability"));
        product.setGtin(attrs.get("gtin"));
        if (attrs.containsKey("brand")) {
            product.setBrand(attrs.get("brand"));
        }
        if (attrs.containsKey("condition")) {
            product.setCondition(attrs.get("condition"));
        }
        product.setContentLanguage(attrs.get("content_language"));
        product.setTargetCountry(attrs.get("target_country"));
        product.setChannel(attrs.get("channel"));

        String[] priceParts = attrs.get("price").split(" ");
        Price price = new Price();
        price.setValue(priceParts[0]);
        price.setCurrency(priceParts[1]);
        product.setPrice(price);

        if (attrs.containsKey("shipping_weight")) {
            String[] weightParts = attrs.get("shipping_weight").split(" ");
            ProductShippingWeight weight = new ProductShippingWeight();
            weight.setValue(Double.parseDouble(weightParts[0]));
            weight.setUnit(weightParts[1]);
            product.setShippingWeight(weight);
        }

        return product;
    }
}
