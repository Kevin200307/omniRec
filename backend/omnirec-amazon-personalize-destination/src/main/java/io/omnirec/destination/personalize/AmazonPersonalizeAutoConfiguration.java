// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;

/**
 * Activates only when omnirec.destinations.amazon-personalize.enabled=true.
 * Adding the jar and flipping that flag is the entire integration — nothing in
 * the core, the API, or either SDK changes.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.destinations.amazon-personalize", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AmazonPersonalizeProperties.class)
public class AmazonPersonalizeAutoConfiguration {

    /**
     * Credentials come from the SDK's default provider chain, so an IAM role or
     * workload identity works with no configuration change and no secret ever
     * written down.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PersonalizeEventsClient personalizeEventsClient(AmazonPersonalizeProperties properties) {
        var builder = PersonalizeEventsClient.builder();
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (properties.getEndpointOverride() != null && !properties.getEndpointOverride().isBlank()) {
            builder.endpointOverride(java.net.URI.create(properties.getEndpointOverride()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AmazonPersonalizeEventMapper amazonPersonalizeEventMapper(ObjectMapper objectMapper, AmazonPersonalizeProperties properties) {
        return new AmazonPersonalizeEventMapper(objectMapper, properties.getPropertyKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    public AmazonPersonalizeDestination amazonPersonalizeDestination(
            PersonalizeEventsClient client,
            AmazonPersonalizeProperties properties,
            AmazonPersonalizeEventMapper mapper
    ) {
        return new AmazonPersonalizeDestination(client, properties, mapper);
    }
}
