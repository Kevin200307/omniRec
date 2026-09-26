// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.config;

import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.tracker.CommerceTracker;
import io.omnirec.tracker.EventSender;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.transport.HttpEventSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for the backend SDK. A merchant adds the dependency, sets
 * two properties, and injects {@link CommerceTracker}:
 *
 * <pre>
 *   omnirec:
 *     tracker:
 *       endpoint: https://events.example.com
 *       api-key: ${OMNIREC_API_KEY}
 *       tenant-id: my-store
 * </pre>
 *
 * Every bean is {@code @ConditionalOnMissingBean}, so a merchant who needs a
 * different transport (a message queue of their own, say) supplies their own
 * {@link EventSender} and keeps the rest.
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
     * Fails fast on missing configuration. A tracker that silently no-ops
     * because the endpoint was never set is the worst outcome: everything looks
     * fine and no data arrives, and nobody notices for a month.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventSender omnirecEventSender(RestTemplate omnirecTrackerRestTemplate, CommerceTrackerProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw new IllegalStateException("omnirec.tracker.endpoint must be set, or set "
                    + "omnirec.tracker.enabled=false to disable backend tracking entirely");
        }
        return new HttpEventSender(omnirecTrackerRestTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventValidator omnirecEventValidator() {
        return new EventValidator();
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
    public CommerceTracker commerceTracker(ServerEventEmitter emitter) {
        return new CommerceTracker(emitter);
    }
}
