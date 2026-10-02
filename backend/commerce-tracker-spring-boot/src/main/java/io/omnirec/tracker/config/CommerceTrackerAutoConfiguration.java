// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.config;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.tracker.CommerceTracker;
import io.omnirec.tracker.EventSender;
import io.omnirec.tracker.OmnirecTracker;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.annotation.TrackEventAspect;
import io.omnirec.tracker.outbox.OutboxEventSender;
import io.omnirec.tracker.outbox.OutboxRelay;
import io.omnirec.tracker.outbox.OutboxStore;
import io.omnirec.tracker.transport.HttpEventSender;
import io.omnirec.tracker.web.OmnirecIdentityFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

/**
 * Backend tracking for a merchant's Spring Boot application. The only required
 * setting is {@code omnirec.tracker.endpoint}.
 *
 * Optional features switch on when their libraries are present: the identity
 * filter (servlet API), {@code @TrackEvent} (Spring AOP) and the transactional
 * outbox ({@code omnirec.tracker.outbox.enabled}, needs spring-jdbc and a
 * PostgreSQL DataSource).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.tracker", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CommerceTrackerProperties.class)
public class CommerceTrackerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "omnirecTrackerRestTemplate")
    public RestTemplate omnirecTrackerRestTemplate() {
        return new RestTemplate();
    }

    /**
     * HTTP delivery to the collector, wrapped in the transactional outbox when
     * it is enabled. Replace this bean to send elsewhere (tests use the recorder).
     */
    @Bean
    @ConditionalOnMissingBean
    public EventSender omnirecEventSender(RestTemplate omnirecTrackerRestTemplate, CommerceTrackerProperties properties,
                                          ObjectProvider<javax.sql.DataSource> dataSource) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw new IllegalStateException("omnirec.tracker.endpoint must be set, or set "
                    + "omnirec.tracker.enabled=false to disable backend tracking entirely");
        }
        HttpEventSender http = new HttpEventSender(omnirecTrackerRestTemplate, properties);
        if (!properties.getOutbox().isEnabled()) return http;
        javax.sql.DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException("omnirec.tracker.outbox.enabled=true needs a DataSource bean");
        }
        return OutboxWiring.create(http, source, properties.getOutbox());
    }

    /** Kept separate so spring-jdbc classes are only loaded when the outbox is on. */
    static final class OutboxWiring {
        static EventSender create(HttpEventSender http, javax.sql.DataSource source,
                                  CommerceTrackerProperties.Outbox outbox) {
            OutboxStore store = new OutboxStore(new org.springframework.jdbc.core.JdbcTemplate(source), outbox.getTable());
            store.createTableIfMissing();
            OutboxRelay relay = new OutboxRelay(store, http,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)),
                    outbox.getBatchSize(), outbox.getRetryInitialInterval(), outbox.getRetryMaxInterval(),
                    Clock.systemUTC());
            relay.start(outbox.getRelayInterval());
            return new ClosingOutboxSender(http, store, relay);
        }
    }

    /** Stops the relay and drains the HTTP queue on shutdown. */
    static final class ClosingOutboxSender extends OutboxEventSender implements AutoCloseable {
        private final HttpEventSender http;
        private final OutboxRelay relay;

        ClosingOutboxSender(HttpEventSender http, OutboxStore store, OutboxRelay relay) {
            super(http, store, relay, Clock.systemUTC());
            this.http = http;
            this.relay = relay;
        }

        @Override
        public void close() {
            relay.close();
            http.close();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public EventValidator omnirecEventValidator(CommerceTrackerProperties properties) {
        return new EventValidator(EventRegistry.standard(), properties.getValidationMode());
    }

    @Bean
    @ConditionalOnMissingBean
    public ServerEventEmitter omnirecServerEventEmitter(
            EventSender sender,
            EventValidator validator,
            CommerceTrackerProperties properties
    ) {
        return new ServerEventEmitter(sender, validator, properties.getTenantId(), properties.isValidateEvents());
    }

    @Bean
    @ConditionalOnMissingBean
    public OmnirecTracker omnirecTracker(ServerEventEmitter emitter) {
        return new OmnirecTracker(emitter);
    }

    /** @deprecated use {@link OmnirecTracker}. */
    @Deprecated
    @Bean
    @ConditionalOnMissingBean
    public CommerceTracker commerceTracker(ServerEventEmitter emitter) {
        return new CommerceTracker(emitter);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "omnirec.tracker.identity-filter", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class IdentityFilterConfiguration {
        @Bean
        public FilterRegistrationBean<OmnirecIdentityFilter> omnirecIdentityFilter() {
            FilterRegistrationBean<OmnirecIdentityFilter> registration =
                    new FilterRegistrationBean<>(new OmnirecIdentityFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    static class TrackEventConfiguration {
        // No @ConditionalOnBean(OmnirecTracker): nested configurations are evaluated
        // before the outer class's beans exist, so it would always be false.
        @Bean
        @ConditionalOnMissingBean
        public TrackEventAspect omnirecTrackEventAspect(OmnirecTracker tracker) {
            return new TrackEventAspect(tracker);
        }
    }
}
