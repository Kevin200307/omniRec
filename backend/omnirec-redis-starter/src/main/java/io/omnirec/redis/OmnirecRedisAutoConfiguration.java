package io.omnirec.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Activates only when omnirec.cache.redis.enabled=true. Named "Omnirec..." to avoid clashing with Spring Boot's own RedisAutoConfiguration. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.cache.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisCacheProperties.class)
public class OmnirecRedisAutoConfiguration {

    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean
    public RedisConnectionFactory omnirecRedisConnectionFactory(RedisCacheProperties props) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            config.setPassword(props.getPassword());
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate omnirecStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(RedisCacheProvider.class)
    public RedisCacheProvider redisCacheProvider(StringRedisTemplate redisTemplate) {
        return new RedisCacheProvider(redisTemplate);
    }
}
