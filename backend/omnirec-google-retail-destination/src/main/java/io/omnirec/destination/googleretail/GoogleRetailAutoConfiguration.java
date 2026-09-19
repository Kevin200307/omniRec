package io.omnirec.destination.googleretail;

import com.google.cloud.retail.v2.UserEventServiceClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * Activates only when omnirec.destinations.google-retail.enabled=true. Adding
 * this jar and flipping the flag is the whole integration — the demonstration
 * that a second provider needs no change to the core, the API, or either SDK.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.destinations.google-retail", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GoogleRetailProperties.class)
public class GoogleRetailAutoConfiguration {

    /** Application Default Credentials: workload identity in production, a key file locally. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public UserEventServiceClient userEventServiceClient() throws IOException {
        return UserEventServiceClient.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleRetailEventMapper googleRetailEventMapper() {
        return new GoogleRetailEventMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleRetailDestination googleRetailDestination(
            UserEventServiceClient client,
            GoogleRetailProperties properties,
            GoogleRetailEventMapper mapper
    ) {
        return new GoogleRetailDestination(client, properties, mapper);
    }
}
