package io.omnirec.destination.recentlyviewed;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Activates only when omnirec.destinations.recently-viewed.enabled=true.
 *
 * The serving cache, the pipeline state, and Spring Boot's default Redis may
 * all be different instances, so this has its own connection, held where Spring
 * Boot's Redis auto-configuration won't find it (see RecentlyViewedRedisConnection).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.destinations.recently-viewed", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RecentlyViewedProperties.class)
public class RecentlyViewedAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RecentlyViewedRedisConnection recentlyViewedRedisConnection(RecentlyViewedProperties properties) {
        return new RecentlyViewedRedisConnection(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecentlyViewedDestination recentlyViewedDestination(
            RecentlyViewedRedisConnection connection,
            RecentlyViewedProperties properties
    ) {
        return new RecentlyViewedDestination(connection.template(), properties);
    }
}
