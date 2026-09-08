package io.omnirec.web;

import io.omnirec.catalog.CatalogProvider;
import io.omnirec.catalog.CatalogSyncService;
import io.omnirec.catalog.CatalogSyncServiceImpl;
import io.omnirec.core.fake.InMemoryCacheProvider;
import io.omnirec.core.fake.InMemoryRecommendationProvider;
import io.omnirec.core.fake.InMemorySearchProvider;
import io.omnirec.core.provider.CacheProvider;
import io.omnirec.core.provider.RecommendationProvider;
import io.omnirec.core.provider.SearchProvider;
import io.omnirec.core.service.PersonalizationService;
import io.omnirec.web.config.OmnirecCatalogProperties;
import io.omnirec.web.config.OmnirecCorsProperties;
import io.omnirec.web.controller.IngestionController;
import io.omnirec.web.controller.RecentlyViewedController;
import io.omnirec.web.controller.RecommendationController;
import io.omnirec.web.controller.SearchController;
import io.omnirec.web.enrichment.RequestContextEnricher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.Executor;

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
                "io.omnirec.algolia.AlgoliaAutoConfiguration",
                "io.omnirec.googlerecai.GoogleRecAiAutoConfiguration",
                "io.omnirec.redis.OmnirecRedisAutoConfiguration"
        }
)
@EnableConfigurationProperties({OmnirecCorsProperties.class, OmnirecCatalogProperties.class})
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

    @Bean
    public IngestionController ingestionController(PersonalizationService service, RequestContextEnricher enricher) {
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

    /**
     * Dedicated, bounded pool for CatalogSyncServiceImpl's fan-out — kept
     * separate from any @Async executor the host app may already define
     * (and from ForkJoinPool.commonPool(), which CompletableFuture.runAsync
     * defaults to and which a library has no business monopolizing).
     */
    @Bean(name = "omnirecCatalogSyncExecutor")
    @ConditionalOnMissingBean(name = "omnirecCatalogSyncExecutor")
    public Executor omnirecCatalogSyncExecutor(OmnirecCatalogProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("omnirec-catalog-sync-");
        executor.setCorePoolSize(props.getThreadPoolSize());
        executor.setMaxPoolSize(props.getThreadPoolSize());
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryTemplate catalogSyncRetryTemplate() {
        return CatalogSyncServiceImpl.defaultRetryTemplate();
    }

    @Bean
    @ConditionalOnMissingBean
    public CatalogSyncService catalogSyncService(
            List<CatalogProvider> catalogProviders,
            @Qualifier("omnirecCatalogSyncExecutor") Executor omnirecCatalogSyncExecutor,
            RetryTemplate catalogSyncRetryTemplate
    ) {
        return new CatalogSyncServiceImpl(catalogProviders, omnirecCatalogSyncExecutor, catalogSyncRetryTemplate);
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
