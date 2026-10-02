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
import io.omnirec.eventapi.plan.PlanLoader;
import io.omnirec.eventapi.tenant.FileTenantRegistry;
import io.omnirec.eventapi.tenant.JdbcTenantRegistry;
import io.omnirec.eventapi.tenant.TenantCatalogs;
import io.omnirec.eventapi.tenant.TenantRegistry;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.eventapi.security.RateLimiter;
import io.omnirec.eventapi.webhook.GenericJsonWebhookAdapter;
import io.omnirec.eventapi.webhook.StripeWebhookAdapter;
import io.omnirec.eventapi.webhook.WebhookAdapter;
import io.omnirec.eventapi.webhook.WebhookController;
import io.omnirec.eventapi.webhook.WebhookProperties;
import org.springframework.beans.factory.ObjectProvider;
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
@EnableConfigurationProperties({EventApiProperties.class, WebhookProperties.class})
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
                .featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .postConfigurer(EventApiAutoConfiguration::keepDecimalScale)
                .build();
    }

    /**
     * Money must keep its exact value and scale ({@code 2400.00}) all the way
     * from the request body through the queue to storage. Jackson buffers record
     * properties before construction, and without this feature that buffer
     * turns every decimal into a double. Applied to Spring Boot's own mapper too,
     * in case it is the one in use.
     */
    @Bean
    public org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer omnirecExactDecimals() {
        return builder -> builder
                .featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .postConfigurer(EventApiAutoConfiguration::keepDecimalScale);
    }

    /**
     * Events are bound one at a time from a JSON tree, so a malformed event
     * fails alone. The default tree factory strips trailing zeros (2400.00
     * becomes 2.4E+3); the exact factory keeps the value as sent.
     */
    static void keepDecimalScale(ObjectMapper mapper) {
        mapper.setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
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
     * Where tenants come from. Replace it with your own {@link TenantRegistry}
     * bean to keep tenants in another system.
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("deprecation")
    public TenantRegistry tenantRegistry(EventApiProperties properties) {
        TenantRegistry registry = switch (properties.getTenantSource()) {
            case FILE -> new FileTenantRegistry(properties);
            case JDBC -> new JdbcTenantRegistry(properties.getJdbc().getUrl(), properties.getJdbc().getUsername(),
                    properties.getJdbc().getPassword(), properties.getJdbc().getTable(),
                    properties.getJdbc().getRefreshInterval(), properties.getDefaultValidationMode(),
                    java.time.Clock.systemUTC());
        };
        if (properties.isAllowAnonymousIngestion()) {
            log.warn("omnirec.events.allow-anonymous-ingestion is deprecated; use omnirec.events.auth-mode=open.");
        }
        EventApiProperties.AuthMode mode = EventApiRequestFilter.effectiveMode(properties, registry);
        if (mode == EventApiProperties.AuthMode.OPEN) {
            boolean anyOrigins = !properties.getCors().getAllowedOrigins().isEmpty()
                    || registry.tenants().stream().anyMatch(t -> !t.allowedOrigins().isEmpty());
            log.info("Event API auth mode: open. Requests without a key go to tenant {}.{}",
                    properties.getDefaultTenantId(), anyOrigins ? ""
                            : " No allowed origins are configured, so browsers may only send events from this"
                            + " collector's own origin, for example through a /omnirec proxy path.");
        } else {
            log.info("Event API auth mode: keys ({} tenant(s)).", registry.tenants().size());
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanLoader planLoader(org.springframework.core.io.ResourceLoader resourceLoader) {
        return new PlanLoader(resourceLoader);
    }

    /** Fails startup on an invalid tracking plan rather than on the first request. */
    @Bean
    @ConditionalOnMissingBean
    public TenantCatalogs tenantCatalogs(TenantRegistry tenants, PlanLoader planLoader, EventApiProperties properties) {
        TenantCatalogs catalogs = new TenantCatalogs(tenants, planLoader, EventRegistry.standard(),
                properties.getDefaultValidationMode(), properties.getDefaultPlanPaths());
        catalogs.validateAll();
        return catalogs;
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
            TenantCatalogs catalogs,
            DeduplicationStore deduplicationStore,
            IdentityResolver identityResolver,
            EventPublisher publisher,
            EventMetrics metrics,
            EventApiProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<io.omnirec.commerce.privacy.ErasureRegistry> erasures
    ) {
        return new EventIngestionService(normalizer, catalogs, deduplicationStore,
                identityResolver, publisher, metrics, properties.getDeduplicationWindow(), objectMapper)
                .withErasures(erasures.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventController eventController(EventIngestionService ingestionService, EventApiProperties properties,
                                           TenantCatalogs catalogs) {
        return new EventController(ingestionService, properties, catalogs);
    }

    /**
     * {@code POST /v1/webhooks/{source}}: the configured Stripe and JSON
     * sources plus any {@link WebhookAdapter} beans the application defines.
     * Without any source the endpoint answers 404 for everything.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookController webhookController(WebhookProperties webhooks, ObjectProvider<WebhookAdapter> customAdapters,
                                               EventIngestionService ingestionService, TenantRegistry tenants,
                                               EventApiProperties properties, ObjectMapper objectMapper) {
        List<WebhookAdapter> adapters = new ArrayList<>(customAdapters.orderedStream().toList());
        if (webhooks.getStripe().isEnabled()) {
            adapters.add(new StripeWebhookAdapter(webhooks.getStripe(), properties.getDefaultTenantId(), objectMapper,
                    java.time.Clock.systemUTC()));
        }
        webhooks.getJson().forEach((source, config) -> adapters.add(
                new GenericJsonWebhookAdapter(source, config, properties.getDefaultTenantId(), objectMapper)));
        WebhookController controller = new WebhookController(adapters, ingestionService, tenants,
                properties.getDefaultTenantId(), properties.getMaxPayloadBytes(), java.time.Clock.systemUTC());
        if (!controller.sources().isEmpty()) log.info("Webhook sources: {}", controller.sources());
        return controller;
    }

    /**
     * Payload cap, API key and rate limit — all before the body is parsed.
     * Registered via FilterRegistrationBean so it runs early in the chain, and
     * only on the event endpoints.
     */
    @Bean
    public FilterRegistrationBean<EventApiRequestFilter> omnirecEventApiRequestFilter(
            TenantRegistry tenants, RateLimiter rateLimiter, EventApiProperties properties) {
        FilterRegistrationBean<EventApiRequestFilter> registration =
                new FilterRegistrationBean<>(new EventApiRequestFilter(tenants, rateLimiter, properties));
        registration.addUrlPatterns("/v1/events", "/v1/events/batch", "/v1/identify", "/v1/catalog");
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
    public WebMvcConfigurer omnirecEventApiCorsConfigurer(EventApiProperties properties, TenantRegistry tenants) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // Global origins plus every tenant's own. A tenant added later through
                // JDBC still passes the origin check in the filter; CORS response
                // headers for it need a restart or a global entry.
                java.util.Set<String> origins = new java.util.LinkedHashSet<>(properties.getCors().getAllowedOrigins());
                tenants.tenants().forEach(t -> origins.addAll(t.allowedOrigins()));
                if (origins.isEmpty()) return;
                String[] allowed = origins.toArray(new String[0]);
                registry.addMapping("/v1/catalog")
                        .allowedOrigins(allowed)
                        .allowedMethods("GET")
                        .allowedHeaders("X-Omnirec-Key")
                        .allowCredentials(false);
                registry.addMapping("/v1/events/**")
                        .allowedOrigins(allowed)
                        .allowedMethods("POST")
                        .allowedHeaders("Content-Type", "X-Omnirec-Key", "X-Omnirec-Tenant")
                        .allowCredentials(false);
                registry.addMapping("/v1/identify")
                        .allowedOrigins(allowed)
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
