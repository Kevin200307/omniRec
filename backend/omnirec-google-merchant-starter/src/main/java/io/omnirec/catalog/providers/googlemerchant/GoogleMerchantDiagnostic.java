package io.omnirec.catalog.providers.googlemerchant;

import com.google.api.services.content.ShoppingContent;
import io.omnirec.catalog.diagnostics.DiagnosticResult;
import io.omnirec.catalog.diagnostics.FeedDiagnostic;
import io.omnirec.googlemerchant.GoogleMerchantProperties;

import java.math.BigInteger;

/** A self-lookup of your own Merchant Center account — about as lightweight and unambiguously read-only as a Content API call gets, while still proving the configured service account actually has API access granted. */
public class GoogleMerchantDiagnostic implements FeedDiagnostic {

    private final ShoppingContent client;
    private final GoogleMerchantProperties properties;

    public GoogleMerchantDiagnostic(ShoppingContent client, GoogleMerchantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "google-merchant";
    }

    @Override
    public DiagnosticResult check() {
        try {
            BigInteger merchantId = new BigInteger(properties.getMerchantId());
            client.accounts().get(merchantId, merchantId).execute();
            return DiagnosticResult.healthy("Content API access confirmed for merchant " + properties.getMerchantId());
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return DiagnosticResult.unhealthy(
                    "Content API access check failed: " + reason,
                    "https://developers.google.com/merchant/api/guides/quickstart"
            );
        }
    }
}
