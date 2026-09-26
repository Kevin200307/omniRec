// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.InMemoryDeduplicationStore;
import io.omnirec.commerce.identity.IdentityLinkStore;
import io.omnirec.commerce.identity.IdentityResolver;
import io.omnirec.commerce.identity.InMemoryIdentityLinkStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.eventapi.controller.EventApiExceptionHandler;
import io.omnirec.eventapi.controller.EventController;
import io.omnirec.eventapi.security.EventApiRequestFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import io.omnirec.eventapi.ingest.EventIngestionService;
import io.omnirec.eventapi.metrics.MicrometerEventMetrics;
import io.omnirec.eventapi.normalize.EventNormalizer;
import io.omnirec.eventapi.queue.EventPublisher;
import io.omnirec.eventapi.security.ApiKeyAuthenticator;
import io.omnirec.eventapi.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wires the synchronous half of the pipeline: authentication, rate limiting,
 * normalization, validation, identity resolution, and handing off to the queue.
 *
 * Ordered after the processing auto-configuration so a real
 * {@link EventPublisher} is in place before ingestion is assembled.
 */
@AutoConfiguration(
        after = {WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class},
        // Strings, not class literals: actuator is optional here, and a class
        // literal for a class that is not on the classpath fails at startup.
        afterName = {
                "io.omnirec.eventprocessing.config.EventProcessingAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
        }
)
@EnableConfigurationProperties(EventApiProperties.class)
public class EventApiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventApiAutoConfiguration.class);

    /**
     * Instants must serialise as ISO-8601 strings, not epoch numbers — the
     * queue payload is read by consumers and by humans in the RabbitMQ console,
     * and a bare number there is unreadable.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper omnirecObjectMapper() {
        return Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .featuresToDisable(
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventValidator eventValidator() {
        return new EventValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityLinkStore identityLinkStore() {
        log.warn("Using the in-memory identity link store. Links are lost on restart and not shared "
                + "between instances. Set omnirec.state.redis.enabled=true (omnirec-redis-state) for any real deployment.");
        return new InMemoryIdentityLinkStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityResolver identityResolver(IdentityLinkStore linkStore) {
        return new IdentityResolver(linkStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeduplicationStore apiDeduplicationStore() {
        return new InMemoryDeduplicationStore();
    }

    /**
     * Binds the pipeline counters to Micrometer when a registry is present, so
     * an application with spring-boot-starter-actuator gets them on
     * /actuator/prometheus with no configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public EventMetrics micrometerEventMetrics(MeterRegistry registry) {
        return new MicrometerEventMetrics(registry);
    }

    /** Fallback when no metrics registry is on the classpath. */
    @Bean
    @ConditionalOnMissingBean({EventMetrics.class, MeterRegistry.class})
    public EventMetrics noopEventMetrics() {
        log.info("No MeterRegistry found — pipeline metrics are disabled. "
                + "Add spring-boot-starter-actuator to enable them.");
        return EventMetrics.noop();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventNormalizer eventNormalizer(EventApiProperties properties) {
        return new EventNormalizer(properties.isRetainIpAddress());
    }

    /**
     * Fails startup if anonymous ingestion is left on outside development.
     * Shipping with tenant isolation silently disabled is exactly the kind of
     * mistake that should never reach production quietly.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiKeyAuthenticator apiKeyAuthenticator(EventApiProperties properties, Environment environment) {
        boolean isDevelopment = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("dev") || profile.equals("test") || profile.equals("local"));

        if (properties.isAllowAnonymousIngestion() && !isDevelopment) {
            throw new IllegalStateException(
                    "omnirec.events.allow-anonymous-ingestion=true disables tenant isolation and is only "
                            + "permitted under the dev, test, or local profile. Configure "
                            + "omnirec.events.tenants.<id>.api-key instead.");
        }

        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(properties);
        if (!authenticator.hasAnyKeyConfigured() && !properties.isAllowAnonymousIngestion()) {
            log.warn("No tenant API keys are configured — every ingestion request will be rejected with 401. "
                    + "Set omnirec.events.tenants.<id>.api-key.");
        }
        return authenticator;
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(EventApiProperties properties) {
        return new RateLimiter(properties.getRateLimit());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventIngestionService eventIngestionService(
            EventNormalizer normalizer,
            EventValidator validator,
            DeduplicationStore deduplicationStore,
            IdentityResolver identityResolver,
            EventPublisher publisher,
            EventMetrics metrics,
            EventApiProperties properties,
            ObjectMapper objectMapper
    ) {
        return new EventIngestionService(normalizer, validator, deduplicationStore,
                identityResolver, publisher, metrics, properties.getDeduplicationWindow(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventController eventController(EventIngestionService ingestionService, EventApiProperties properties) {
        return new EventController(ingestionService, properties);
    }

    /**
     * Payload cap, API key and rate limit — all before the body is parsed.
     * Registered via FilterRegistrationBean so it runs early in the chain, and
     * only on the event endpoints.
     */
    @Bean
    public FilterRegistrationBean<EventApiRequestFilter> omnirecEventApiRequestFilter(
            ApiKeyAuthenticator authenticator, RateLimiter rateLimiter, EventApiProperties properties) {
        FilterRegistrationBean<EventApiRequestFilter> registration =
                new FilterRegistrationBean<>(new EventApiRequestFilter(authenticator, rateLimiter, properties));
        registration.addUrlPatterns("/v1/events", "/v1/events/batch", "/v1/identify");
        // After CORS (which answers preflights) but before anything that reads the body.
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public EventApiExceptionHandler eventApiExceptionHandler() {
        return new EventApiExceptionHandler();
    }

    /**
     * Only the event endpoints are CORS-exposed, and only to configured
     * storefront origins. Credentials are not allowed: the API key travels in a
     * header, so there is no reason to let the browser attach cookies.
     */
    @Bean
    public WebMvcConfigurer omnirecEventApiCorsConfigurer(EventApiProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (properties.getCors().getAllowedOrigins().isEmpty()) return;
                registry.addMapping("/v1/events/**")
                        .allowedOrigins(properties.getCors().getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods("POST")
                        .allowedHeaders("Content-Type", "X-Omnirec-Key", "X-Omnirec-Tenant")
                        .allowCredentials(false);
                registry.addMapping("/v1/identify")
                        .allowedOrigins(properties.getCors().getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods("POST")
                        .allowedHeaders("Content-Type", "X-Omnirec-Key", "X-Omnirec-Tenant")
                        .allowCredentials(false);
            }
        };
    }

    /**
     * Lets the JSON converter read a {@code text/plain} body.
     *
     * {@code navigator.sendBeacon} — the only reliable way to flush events as a
     * page unloads, and therefore the path the last events of every visit take —
     * cannot set a Content-Type header. A beacon must stay a CORS "simple
     * request", which permits only text/plain, multipart, or form-urlencoded.
     * The body is still JSON; only the declared type differs, so we teach the
     * existing converter to accept it rather than adding a second endpoint.
     */
    @Bean
    public WebMvcConfigurer omnirecBeaconMediaTypeConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                for (HttpMessageConverter<?> converter : converters) {
                    if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                        List<MediaType> supported = new ArrayList<>(jackson.getSupportedMediaTypes());
                        if (!supported.contains(MediaType.TEXT_PLAIN)) {
                            supported.add(MediaType.TEXT_PLAIN);
                            jackson.setSupportedMediaTypes(supported);
                        }
                    }
                }
            }
        };
    }

    /** Keeps the rate-limiter's window map from growing without bound. */
    @Bean
    public RateLimiterEvictionTask rateLimiterEvictionTask(RateLimiter rateLimiter) {
        return new RateLimiterEvictionTask(rateLimiter);
    }

    public static class RateLimiterEvictionTask {
        private final RateLimiter rateLimiter;

        public RateLimiterEvictionTask(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
        }

        @Scheduled(fixedDelayString = "PT5M")
        public void evict() {
            rateLimiter.evictExpired();
        }
    }
}
