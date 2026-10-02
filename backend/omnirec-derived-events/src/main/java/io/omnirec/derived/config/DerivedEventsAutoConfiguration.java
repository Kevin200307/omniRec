// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.config;

import io.omnirec.derived.DerivedEventRule;
import io.omnirec.derived.DerivedEventsEngine;
import io.omnirec.derived.rules.CartAbandonedRule;
import io.omnirec.derived.rules.CheckoutAbandonedRule;
import io.omnirec.derived.rules.PurchaseHistoryRule;
import io.omnirec.derived.rules.ReturnVisitRule;
import io.omnirec.derived.store.DerivedStateStore;
import io.omnirec.derived.store.InMemoryDerivedStateStore;
import io.omnirec.derived.store.RedisDerivedStateStore;
import io.omnirec.eventapi.queue.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code omnirec.derived.enabled=true} adds the derived-events engine as a
 * pipeline destination, with the built-in rules and any
 * {@link DerivedEventRule} beans, and a poller that fires due timers.
 */
@AutoConfiguration(afterName = "io.omnirec.redis.state.RedisStateAutoConfiguration",
        beforeName = "io.omnirec.eventprocessing.config.EventProcessingAutoConfiguration")
@ConditionalOnProperty(prefix = "omnirec.derived", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DerivedEventsProperties.class)
public class DerivedEventsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DerivedEventsAutoConfiguration.class);
    private static final String REDIS_TEMPLATE = "omnirecStateRedisTemplate";

    @Bean
    @ConditionalOnMissingBean
    public DerivedStateStore derivedStateStore(DerivedEventsProperties properties, BeanFactory beans) {
        boolean redisAvailable = beans.containsBean(REDIS_TEMPLATE);
        DerivedEventsProperties.Store store = properties.getStore();
        if (store == DerivedEventsProperties.Store.REDIS && !redisAvailable) {
            throw new IllegalStateException("omnirec.derived.store=redis needs omnirec-redis-state with "
                    + "omnirec.state.redis.enabled=true");
        }
        if (store != DerivedEventsProperties.Store.MEMORY && redisAvailable) {
            return RedisStores.create(beans, properties.getRedisKeyPrefix());
        }
        log.warn("Derived-event timers and counters are kept in memory: they are lost on restart and not shared "
                + "between instances. Enable omnirec.state.redis for anything beyond one instance.");
        return new InMemoryDerivedStateStore(Clock.systemUTC());
    }

    @Bean
    public DerivedEventsEngine derivedEventsEngine(DerivedEventsProperties properties, DerivedStateStore store,
                                                   ObjectProvider<DerivedEventRule> customRules,
                                                   ObjectProvider<EventPublisher> publisher) {
        List<DerivedEventRule> rules = new ArrayList<>();
        if (properties.getCartAbandoned().isEnabled()) {
            rules.add(new CartAbandonedRule(properties.getCartAbandoned().getTimeout()));
        }
        if (properties.getCheckoutAbandoned().isEnabled()) {
            rules.add(new CheckoutAbandonedRule(properties.getCheckoutAbandoned().getTimeout()));
        }
        if (properties.getReturnVisit().isEnabled()) {
            rules.add(new ReturnVisitRule(properties.getReturnVisit().getMinimumGap()));
        }
        if (properties.getPurchaseHistory().isEnabled()) rules.add(new PurchaseHistoryRule());
        customRules.orderedStream().forEach(rules::add);

        // The publisher is looked up on first use: it is built from the list of
        // destinations, and this engine is one of them.
        DerivedEventsEngine engine = new DerivedEventsEngine(rules, store,
                event -> publisher.getObject().publish(event), Clock.systemUTC());
        log.info("Derived events on: {}", engine.ruleNames());
        return engine;
    }

    /**
     * Keeps every Spring Data Redis type out of the configuration class, so it
     * loads on a classpath without Redis (the dependency is optional).
     */
    static final class RedisStores {
        static DerivedStateStore create(BeanFactory beans, String prefix) {
            return new RedisDerivedStateStore(
                    beans.getBean(REDIS_TEMPLATE, org.springframework.data.redis.core.StringRedisTemplate.class), prefix);
        }
    }

    @Bean
    public DerivedEventsPoller derivedEventsPoller(DerivedEventsEngine engine, DerivedEventsProperties properties) {
        return new DerivedEventsPoller(engine, properties.getPollInterval());
    }
}
