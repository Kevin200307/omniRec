// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.InMemoryDeduplicationStore;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventapi.queue.EventPublisher;
import io.omnirec.eventprocessing.consumer.DestinationConsumer;
import io.omnirec.eventprocessing.consumer.DestinationListeners;
import io.omnirec.eventprocessing.dispatch.EventDispatcher;
import io.omnirec.eventprocessing.queue.DirectEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.eventprocessing.metrics.QueueDepthGauges;
import io.omnirec.eventprocessing.queue.ConfirmedPublisher;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RabbitEventPublisher;
import io.omnirec.eventprocessing.queue.RetrySchedule;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the asynchronous half of the pipeline.
 *
 * Two mutually exclusive modes:
 * <ul>
 *   <li><b>queue-enabled (default)</b> — a durable queue, retry queue and DLQ
 *       per destination, plus one listener container each.</li>
 *   <li><b>queue-disabled</b> — destinations are called inline. Local
 *       development only; logged loudly because it silently gives up durability.</li>
 * </ul>
 */
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@EnableConfigurationProperties(EventProcessingProperties.class)
public class EventProcessingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public DeduplicationStore deduplicationStore() {
        log.warn("Using the in-memory deduplication store. It is per-process, so with more than "
                + "one instance the same event can be delivered once per instance. "
                + "Set omnirec.state.redis.enabled=true (omnirec-redis-state) for any multi-instance deployment.");
        return new InMemoryDeduplicationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventDispatcher eventDispatcher(
            DeduplicationStore deduplicationStore,
            EventMetrics metrics,
            EventProcessingProperties properties
    ) {
        return new EventDispatcher(deduplicationStore, metrics,
                properties.getDeduplicationWindow(), properties.getDeliveryLease());
    }

    /** Inline delivery. Correct, but with no durability or retry — see DirectEventPublisher. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "omnirec.processing", name = "queue-enabled", havingValue = "false")
    static class DirectDeliveryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EventPublisher directEventPublisher(List<EventDestination> destinations, EventDispatcher dispatcher) {
            log.warn("omnirec.processing.queue-enabled=false — events are delivered inline with no "
                    + "retry, dead-lettering, or durability. Do not run a deployment this way.");
            return new DirectEventPublisher(destinations, dispatcher);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "omnirec.processing", name = "queue-enabled",
            havingValue = "true", matchIfMissing = true)
    static class RabbitDeliveryConfiguration {

        /** JSON on the wire so a message can be inspected in the RabbitMQ console and read by any language. */
        @Bean
        @ConditionalOnMissingBean
        public MessageConverter omnirecMessageConverter(ObjectMapper objectMapper) {
            return new Jackson2JsonMessageConverter(objectMapper);
        }

        /**
         * Customises Spring Boot's own RabbitTemplate rather than defining a
         * second one: two templates would make every by-type injection
         * ambiguous. Mandatory, so an unroutable message is returned to us
         * instead of silently dropped by the broker.
         */
        @Bean
        public RabbitTemplateCustomizer omnirecRabbitTemplateCustomizer() {
            return template -> template.setMandatory(true);
        }

        @Bean
        public RetrySchedule omnirecRetrySchedule(EventProcessingProperties properties) {
            return new RetrySchedule(properties.getMaxRetries(),
                    properties.getRetryInitialInterval(), properties.getRetryMaxInterval());
        }

        /**
         * Refuses to start without publisher confirms and returns. Without them
         * {@link ConfirmedPublisher} could not know whether the broker took a
         * message, and publishing unconfirmed is exactly the silent-loss bug
         * this exists to prevent.
         */
        @Bean
        public ConfirmedPublisher omnirecConfirmedPublisher(
                RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, EventProcessingProperties properties) {
            if (!connectionFactory.isPublisherConfirms() || !connectionFactory.isPublisherReturns()) {
                throw new IllegalStateException("RabbitMQ publisher confirms and returns are required: set "
                        + "spring.rabbitmq.publisher-confirm-type=correlated and spring.rabbitmq.publisher-returns=true");
            }
            return new ConfirmedPublisher(rabbitTemplate, properties.getConfirmTimeout());
        }

        /**
         * Per destination: the main queue, one retry tier per attempt, and the DLQ.
         * Declaration is idempotent, so restarts are harmless.
         */
        @Bean
        public Declarables omnirecQueueTopology(List<EventDestination> destinations, RetrySchedule retrySchedule) {
            List<Declarable> declarables = new ArrayList<>();

            TopicExchange exchange = new TopicExchange(QueueTopology.EXCHANGE, true, false);
            TopicExchange deadLetterExchange = new TopicExchange(QueueTopology.DEAD_LETTER_EXCHANGE, true, false);
            declarables.add(exchange);
            declarables.add(deadLetterExchange);

            for (EventDestination destination : destinations) {
                String id = destination.id();
                String routingKey = QueueTopology.routingKey(id);

                // The main queue dead-letters anything rejected without requeue
                // (an unparseable message) to the DLQ, instead of the broker
                // silently discarding it.
                Queue main = QueueBuilder.durable(QueueTopology.queueName(id))
                        .deadLetterExchange(QueueTopology.DEAD_LETTER_EXCHANGE)
                        .deadLetterRoutingKey(routingKey)
                        .build();
                Queue deadLetter = QueueBuilder.durable(QueueTopology.deadLetterQueueName(id)).build();

                declarables.add(main);
                declarables.add(deadLetter);
                declarables.add(BindingBuilder.bind(main).to(exchange).with(routingKey));
                declarables.add(BindingBuilder.bind(deadLetter).to(deadLetterExchange).with(routingKey));

                // One tier per attempt, each with a single queue-level TTL. A
                // queue only expires messages at its head, so mixing delays in
                // one queue would hold short retries behind long ones.
                for (int attempt = 1; attempt <= retrySchedule.maxRetries(); attempt++) {
                    declarables.add(QueueBuilder.durable(QueueTopology.retryQueueName(id, attempt))
                            .ttl((int) retrySchedule.delayFor(attempt).toMillis())
                            .deadLetterExchange(QueueTopology.EXCHANGE)
                            .deadLetterRoutingKey(routingKey)
                            .build());
                }
            }
            return new Declarables(declarables);
        }

        /** Moves dead-lettered events back for another try; see DeadLetterEndpoint. */
        @Bean
        @ConditionalOnMissingBean
        public io.omnirec.eventprocessing.queue.DeadLetterReplayer omnirecDeadLetterReplayer(
                ConnectionFactory connectionFactory, List<EventDestination> destinations) {
            return new io.omnirec.eventprocessing.queue.DeadLetterReplayer(connectionFactory,
                    destinations.stream().map(EventDestination::id).toList());
        }

        @Configuration(proxyBeanMethods = false)
        @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
                name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
        static class DeadLetterEndpointConfiguration {
            @Bean
            @ConditionalOnMissingBean
            public io.omnirec.eventprocessing.queue.DeadLetterEndpoint omnirecDeadLetterEndpoint(
                    io.omnirec.eventprocessing.queue.DeadLetterReplayer replayer) {
                return new io.omnirec.eventprocessing.queue.DeadLetterEndpoint(replayer);
            }
        }

        @Bean
        @ConditionalOnMissingBean
        public EventPublisher rabbitEventPublisher(ConfirmedPublisher publisher, List<EventDestination> destinations) {
            if (destinations.isEmpty()) {
                log.warn("No EventDestination beans are active; events will be accepted and queued nowhere. "
                        + "Enable at least one destination starter.");
            }
            return new RabbitEventPublisher(publisher, destinations);
        }

        /**
         * One listener container per destination, started and stopped with the
         * application by {@link DestinationListeners}.
         *
         * {@link AcknowledgeMode#AUTO} is Spring's container-managed ack, not
         * RabbitMQ's fire-and-forget autoAck ({@link AcknowledgeMode#NONE}):
         * the container acks only when the listener returns normally. The
         * consumer returns normally only once the event is delivered, skipped,
         * or confirmed onto a retry tier or the DLQ; anything else throws, the
         * container nacks, and the broker redelivers.
         */
        @Bean
        public DestinationListeners omnirecDestinationListeners(
                ConnectionFactory connectionFactory,
                ObjectMapper objectMapper,
                ConfirmedPublisher publisher,
                EventDispatcher dispatcher,
                EventMetrics metrics,
                EventProcessingProperties properties,
                RetrySchedule retrySchedule,
                List<EventDestination> destinations
        ) {
            List<SimpleMessageListenerContainer> containers = new ArrayList<>();

            for (EventDestination destination : destinations) {
                DestinationConsumer consumer = new DestinationConsumer(
                        destination, dispatcher, publisher, metrics, retrySchedule);

                SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
                container.setQueueNames(QueueTopology.queueName(destination.id()));
                container.setConcurrentConsumers(properties.getConcurrency());
                container.setPrefetchCount(properties.getPrefetchCount());
                container.setAcknowledgeMode(AcknowledgeMode.AUTO);
                container.setMessageListener((Message message) -> {
                    CommerceEvent event;
                    try {
                        // Always CommerceEvent, never a class named by a message
                        // header: letting a header choose what is deserialised is
                        // a classic gadget-chain hole.
                        event = objectMapper.readValue(message.getBody(), CommerceEvent.class);
                    } catch (java.io.IOException e) {
                        // It will never parse. Reject without requeue; the main
                        // queue's DLX moves it to the DLQ for inspection.
                        log.error("Unparseable message on {} sent to the dead-letter queue: {}",
                                QueueTopology.queueName(destination.id()), e.getMessage());
                        metrics.deadLettered(destination.id(), "unparseable");
                        throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                                "unparseable event message", e);
                    }
                    consumer.handle(event, message);
                });
                container.setAutoStartup(false);
                containers.add(container);
            }
            return new DestinationListeners(containers);
        }

        /**
         * Queue depth per destination (main, retry tiers and DLQ) as gauges.
         * A growing retry or DLQ depth is how you notice a provider outage.
         */
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        public QueueDepthGauges omnirecQueueDepthGauges(
                MeterRegistry registry, AmqpAdmin amqpAdmin, List<EventDestination> destinations, RetrySchedule retrySchedule) {
            return new QueueDepthGauges(registry, amqpAdmin, destinations, retrySchedule);
        }
    }
}
