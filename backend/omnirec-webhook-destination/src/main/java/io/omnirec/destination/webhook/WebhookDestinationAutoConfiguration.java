// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * {@code omnirec.destinations.webhook.enabled=true} registers one
 * {@link WebhookDestination} bean per configured endpoint, named
 * {@code webhook-<name>}. Separate beans, so each endpoint gets its own queue,
 * retry tiers and dead-letter queue from the processing module.
 */
@AutoConfiguration(beforeName = "io.omnirec.eventprocessing.config.EventProcessingAutoConfiguration")
@ConditionalOnProperty(prefix = "omnirec.destinations.webhook", name = "enabled", havingValue = "true")
public class WebhookDestinationAutoConfiguration {

    @Bean
    public static WebhookEndpointRegistrar omnirecWebhookEndpointRegistrar() {
        return new WebhookEndpointRegistrar();
    }

    /** Binds the endpoints before beans are created and registers a destination for each. */
    public static class WebhookEndpointRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

        private static final Logger log = LoggerFactory.getLogger(WebhookEndpointRegistrar.class);

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            WebhookDestinationProperties properties = Binder.get(environment)
                    .bind("omnirec.destinations.webhook", WebhookDestinationProperties.class)
                    .orElseGet(WebhookDestinationProperties::new);
            if (properties.getEndpoints().isEmpty()) {
                log.warn("omnirec.destinations.webhook is enabled but no endpoints are configured");
                return;
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            properties.getEndpoints().forEach((name, endpoint) -> {
                // Built now, so a bad URL or missing secret fails startup, not the first delivery.
                WebhookDestination destination = new WebhookDestination(name, endpoint, http, mapper, Clock.systemUTC());
                RootBeanDefinition definition = new RootBeanDefinition(WebhookDestination.class, () -> destination);
                registry.registerBeanDefinition(destination.id(), definition);
                log.info("Webhook destination {} -> {} for events {}", destination.id(), endpoint.getUrl(),
                        endpoint.getEvents());
            });
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
