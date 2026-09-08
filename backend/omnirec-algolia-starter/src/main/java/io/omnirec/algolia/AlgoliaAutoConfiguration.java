package io.omnirec.algolia;

import com.algolia.api.SearchClient;
import io.omnirec.catalog.providers.algolia.AlgoliaCatalogProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.search.algolia", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AlgoliaProperties.class)
public class AlgoliaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SearchClient algoliaSearchClient(AlgoliaProperties props) {
        return new SearchClient(props.getAppId(), props.getApiKey());
    }

    @Bean
    @ConditionalOnMissingBean(AlgoliaSearchProvider.class)
    public AlgoliaSearchProvider algoliaSearchProvider(SearchClient client, AlgoliaProperties props) {
        return new AlgoliaSearchProvider(client, props);
    }

    /**
     * Registered under the same enabled flag as search — catalog sync and
     * search share one Algolia account/index, so there's no separate
     * "enable catalog sync for Algolia" switch to remember.
     */
    @Bean
    @ConditionalOnMissingBean(AlgoliaCatalogProvider.class)
    public AlgoliaCatalogProvider algoliaCatalogProvider(SearchClient client, AlgoliaProperties props) {
        return new AlgoliaCatalogProvider(client, props);
    }
}
