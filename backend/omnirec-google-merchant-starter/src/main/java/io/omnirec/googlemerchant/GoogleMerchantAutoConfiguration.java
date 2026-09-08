package io.omnirec.googlemerchant;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.content.ShoppingContent;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import io.omnirec.catalog.providers.googlemerchant.GoogleMerchantCatalogProvider;
import io.omnirec.catalog.providers.googlemerchant.GoogleMerchantDiagnostic;
import io.omnirec.catalog.providers.googlemerchant.GoogleMerchantFeedFileProvider;
import io.omnirec.catalog.providers.googlemerchant.GoogleMerchantItemMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Activates only when omnirec.providers.google-merchant.enabled=true.
 * Registers exactly one of GoogleMerchantCatalogProvider (mode=push,
 * default) or GoogleMerchantFeedFileProvider (mode=scheduled-fetch) — never
 * both, per the mode switch. The ShoppingContent client and
 * GoogleMerchantDiagnostic are created unconditionally on mode, since
 * diagnostics are useful regardless of which one is active.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.providers.google-merchant", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GoogleMerchantProperties.class)
public class GoogleMerchantAutoConfiguration {

    private static final List<String> CONTENT_API_SCOPES = List.of("https://www.googleapis.com/auth/content");

    @Bean
    @ConditionalOnMissingBean
    public GoogleMerchantItemMapper googleMerchantItemMapper() {
        return new GoogleMerchantItemMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShoppingContent shoppingContentClient(GoogleMerchantProperties props) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        HttpRequestInitializer credentials = new HttpCredentialsAdapter(loadCredentials(props));
        return new ShoppingContent.Builder(transport, jsonFactory, credentials)
                .setApplicationName("omnirec")
                .build();
    }

    /** Default mode — matchIfMissing=true because GoogleMerchantProperties.mode's field default ("push") only applies once Spring binds the object; @ConditionalOnProperty checks the raw Environment value, which is absent (not "push") when a developer never sets this key at all. */
    @Bean
    @ConditionalOnProperty(prefix = "omnirec.providers.google-merchant", name = "mode", havingValue = "push", matchIfMissing = true)
    @ConditionalOnMissingBean(GoogleMerchantCatalogProvider.class)
    public GoogleMerchantCatalogProvider googleMerchantCatalogProvider(
            ShoppingContent client, GoogleMerchantItemMapper mapper, GoogleMerchantProperties props) {
        return new GoogleMerchantCatalogProvider(client, mapper, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "omnirec.providers.google-merchant", name = "mode", havingValue = "scheduled-fetch")
    @ConditionalOnMissingBean(GoogleMerchantFeedFileProvider.class)
    public GoogleMerchantFeedFileProvider googleMerchantFeedFileProvider(GoogleMerchantItemMapper mapper, GoogleMerchantProperties props) {
        return new GoogleMerchantFeedFileProvider(mapper, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleMerchantDiagnostic googleMerchantDiagnostic(ShoppingContent client, GoogleMerchantProperties props) {
        return new GoogleMerchantDiagnostic(client, props);
    }

    private GoogleCredentials loadCredentials(GoogleMerchantProperties props) throws IOException {
        try (FileInputStream in = new FileInputStream(props.getCredentialsFile())) {
            return GoogleCredentials.fromStream(in).createScoped(CONTENT_API_SCOPES);
        }
    }
}
