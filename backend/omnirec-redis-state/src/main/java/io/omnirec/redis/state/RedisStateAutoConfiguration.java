package io.omnirec.redis.state;

import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.identity.IdentityLinkStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Replaces the in-memory deduplication and identity-link stores with shared
 * Redis-backed ones.
 *
 * Ordered <em>before</em> the Event API and processing auto-configurations, so
 * these beans exist by the time their {@code @ConditionalOnMissingBean}
 * fallbacks are evaluated and the in-memory versions never register.
 *
 * Required for any deployment running more than one instance: the in-memory
 * stores are per-process, so without this the same event can be delivered once
 * per instance and one instance's identity links are invisible to the others.
 * Both in-memory stores log a warning when they are the ones active.
 */
@AutoConfiguration(before = {
        io.omnirec.eventapi.config.EventApiAutoConfiguration.class,
        io.omnirec.eventprocessing.config.EventProcessingAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "omnirec.state.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisStateProperties.class)
public class RedisStateAutoConfiguration {

    /**
     * Named distinctly so it doesn't clash with Spring Boot's own
     * RedisAutoConfiguration or with omnirec-redis-starter's cache connection —
     * the pipeline's shared state and the serving-side cache may well point at
     * different Redis instances.
     */
    @Bean(name = "omnirecStateRedisConnectionFactory", destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "omnirecStateRedisConnectionFactory")
    public RedisConnectionFactory omnirecStateRedisConnectionFactory(RedisStateProperties properties) {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            config.setPassword(properties.getPassword());
        }
        if (properties.getDatabase() != null) {
            config.setDatabase(properties.getDatabase());
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean(name = "omnirecStateRedisTemplate")
    @ConditionalOnMissingBean(name = "omnirecStateRedisTemplate")
    public StringRedisTemplate omnirecStateRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("omnirecStateRedisConnectionFactory")
            RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeduplicationStore redisDeduplicationStore(
            @org.springframework.beans.factory.annotation.Qualifier("omnirecStateRedisTemplate")
            StringRedisTemplate redisTemplate
    ) {
        return new RedisDeduplicationStore(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityLinkStore redisIdentityLinkStore(
            @org.springframework.beans.factory.annotation.Qualifier("omnirecStateRedisTemplate")
            StringRedisTemplate redisTemplate,
            RedisStateProperties properties
    ) {
        return new RedisIdentityLinkStore(redisTemplate, properties.getIdentityLinkTtl());
    }
}
