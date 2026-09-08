package io.omnirec.personalize;

import io.omnirec.catalog.providers.personalize.PersonalizeCatalogProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeruntime.PersonalizeRuntimeClient;

/**
 * Activates only when omnirec.recommendation.aws-personalize.enabled=true —
 * this is the entire integration surface for AWS Personalize. Add the jar,
 * flip this flag, done.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.recommendation.aws-personalize", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PersonalizeProperties.class)
public class PersonalizeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PersonalizeRuntimeClient personalizeRuntimeClient(PersonalizeProperties props) {
        return PersonalizeRuntimeClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalizeEventsClient personalizeEventsClient(PersonalizeProperties props) {
        return PersonalizeEventsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .build();
    }

    /**
     * Deliberately not gated on @ConditionalOnMissingBean(RecommendationProvider.class):
     * PersonalizationService fans out to every active RecommendationProvider bean, so a
     * developer can run this alongside omnirec-google-recai-starter (e.g. for an A/B test)
     * without one silently shadowing the other.
     */
    @Bean
    @ConditionalOnMissingBean(AmazonPersonalizeProvider.class)
    public AmazonPersonalizeProvider amazonPersonalizeProvider(
            PersonalizeRuntimeClient runtimeClient, PersonalizeEventsClient eventsClient, PersonalizeProperties props) {
        return new AmazonPersonalizeProvider(runtimeClient, eventsClient, props);
    }

    /** Registered under the same enabled flag as recommendations — see AmazonPersonalizeProvider's bean method note above for why this isn't @ConditionalOnMissingBean(CatalogProvider.class). */
    @Bean
    @ConditionalOnMissingBean(PersonalizeCatalogProvider.class)
    public PersonalizeCatalogProvider personalizeCatalogProvider(PersonalizeEventsClient eventsClient, PersonalizeProperties props) {
        return new PersonalizeCatalogProvider(eventsClient, props);
    }
}
