// SPDX-License-Identifier: Apache-2.0
package io.omnirec.web;

import io.omnirec.core.fake.InMemoryCacheProvider;
import io.omnirec.core.fake.InMemoryRecommendationProvider;
import io.omnirec.core.fake.InMemorySearchProvider;
import io.omnirec.core.provider.CacheProvider;
import io.omnirec.core.provider.RecommendationProvider;
import io.omnirec.core.provider.SearchProvider;
import io.omnirec.core.service.PersonalizationService;
import io.omnirec.web.config.OmnirecCorsProperties;
import io.omnirec.web.controller.IngestionController;
import io.omnirec.web.controller.RecentlyViewedController;
import io.omnirec.web.controller.RecommendationController;
import io.omnirec.web.controller.SearchController;
import io.omnirec.web.enrichment.RequestContextEnricher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the REST layer plus a zero-config fallback: if no real
 * RecommendationProvider/SearchProvider/CacheProvider bean is on the
 * classpath (no starter added, or one added but disabled), the in-memory
 * fakes step in so the app runs instead of failing to start. This is
 * ordered after every provider starter's own auto-configuration via
 * afterName, so "no real provider found" is evaluated only once every
 * starter has had its chance to register one.
 */
@AutoConfiguration(
        after = WebMvcAutoConfiguration.class,
        afterName = {
                "io.omnirec.personalize.PersonalizeAutoConfiguration",
                "io.omnirec.googlerecai.GoogleRecAiAutoConfiguration",
                "io.omnirec.redis.OmnirecRedisAutoConfiguration"
        }
)
@EnableConfigurationProperties(OmnirecCorsProperties.class)
public class OmnirecWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestContextEnricher requestContextEnricher() {
        return new RequestContextEnricher();
    }

    @Bean
    @ConditionalOnMissingBean(RecommendationProvider.class)
    public InMemoryRecommendationProvider inMemoryRecommendationProvider() {
        return new InMemoryRecommendationProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SearchProvider.class)
    public InMemorySearchProvider inMemorySearchProvider() {
        return new InMemorySearchProvider();
    }

    @Bean
    @ConditionalOnMissingBean(CacheProvider.class)
    public InMemoryCacheProvider inMemoryCacheProvider() {
        return new InMemoryCacheProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalizationService personalizationService(
            List<RecommendationProvider> recommendationProviders,
            List<SearchProvider> searchProviders,
            List<CacheProvider> cacheProviders
    ) {
        return new PersonalizationService(recommendationProviders, searchProviders, cacheProviders);
    }

    /**
     * Legacy ingestion — off by default.
     *
     * This endpoint predates the Event API and bypasses everything it
     * guarantees: no API-key authentication (it trusts the body's tenantId),
     * no validation, no deduplication, no queue, and synchronous provider calls
     * on the request thread. Events belong on omnirec-event-api-app. Enable this
     * only to keep an old integration alive while migrating it.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "omnirec.web.legacy-ingestion", name = "enabled", havingValue = "true")
    public IngestionController ingestionController(PersonalizationService service, RequestContextEnricher enricher) {
        org.slf4j.LoggerFactory.getLogger(OmnirecWebAutoConfiguration.class).warn(
                "omnirec.web.legacy-ingestion.enabled=true: the unauthenticated legacy POST /v1/events is live. "
                        + "It skips authentication, validation, deduplication and the queue. Migrate to the Event API.");
        return new IngestionController(service, enricher);
    }

    @Bean
    public SearchController searchController(PersonalizationService service) {
        return new SearchController(service);
    }

    @Bean
    public RecommendationController recommendationController(PersonalizationService service) {
        return new RecommendationController(service);
    }

    @Bean
    public RecentlyViewedController recentlyViewedController(PersonalizationService service) {
        return new RecentlyViewedController(service);
    }

    @Bean
    public WebMvcConfigurer omnirecCorsConfigurer(OmnirecCorsProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (props.getAllowedOrigins().isEmpty()) return;
                registry.addMapping("/v1/**")
                        .allowedOrigins(props.getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods("GET", "POST");
            }
        };
    }
}
