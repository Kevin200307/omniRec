package io.omnirec.destination.recentlyviewed;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Owns the connection to the serving cache.
 *
 * Deliberately not a {@code RedisConnectionFactory} bean. Spring Boot's own Redis
 * auto-configuration injects the single connection factory in the context; with
 * the state store's factory already there, a second one stops the application
 * from starting. Holding it here keeps it out of that lookup.
 */
public class RecentlyViewedRedisConnection implements DisposableBean {

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    public RecentlyViewedRedisConnection(RecentlyViewedProperties properties) {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            config.setPassword(properties.getPassword());
        }
        if (properties.getDatabase() != null) {
            config.setDatabase(properties.getDatabase());
        }
        this.factory = new LettuceConnectionFactory(config);
        this.factory.afterPropertiesSet();
        this.template = new StringRedisTemplate(factory);
    }

    public StringRedisTemplate template() {
        return template;
    }

    @Override
    public void destroy() {
        factory.destroy();
    }
}
