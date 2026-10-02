// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * Sends events from any Java backend, no framework required.
 *
 * <pre>{@code
 * OmnirecClient omnirec = OmnirecClient.builder()
 *         .endpoint("https://events.example.com")
 *         .build();
 *
 * omnirec.track(StandardEvents.PURCHASE_COMPLETED,
 *         Map.of("order", Map.of("id", order.id(), "total", order.total(), "currency", "USD",
 *                                "items", lines)),
 *         ServerIdentity.of(order.anonymousId(), order.customerId()));
 * }</pre>
 *
 * Events are validated against the catalog before sending; an invalid event
 * throws at the call site. Names the catalog does not know (custom events from
 * a tracking plan) pass, and the collector checks them against the plan.
 */
public final class OmnirecClient implements AutoCloseable {

    private final ServerEventEmitter emitter;
    private final EventSender sender;

    private OmnirecClient(ServerEventEmitter emitter, EventSender sender) {
        this.emitter = emitter;
        this.sender = sender;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String track(String event, Map<String, ?> data, ServerIdentity identity) {
        return emitter.track(event, data, identity);
    }

    /**
     * @param businessKey derives a stable eventId, so a fact reported twice deduplicates
     */
    public String track(String event, Map<String, ?> data, ServerIdentity identity, Map<String, Object> properties,
                        String businessKey) {
        return emitter.track(event, EventData.of(data), identity, properties, businessKey);
    }

    public void identify(String anonymousId, String userId) {
        emitter.identify(anonymousId, userId);
    }

    public void flush() {
        sender.flush();
    }

    @Override
    public void close() throws Exception {
        if (sender instanceof AutoCloseable closeable) closeable.close();
        else sender.flush();
    }

    public ServerEventEmitter emitter() {
        return emitter;
    }

    public static final class Builder {
        private String endpoint;
        private String apiKey;
        private String tenantId;
        private boolean async = true;
        private boolean validate = true;
        private EventSender sender;

        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        /** Only needed when the collector runs in keys mode. */
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        /** Send on the calling thread instead of a background worker. Default async. */
        public Builder synchronous() { this.async = false; return this; }
        public Builder validateEvents(boolean validate) { this.validate = validate; return this; }
        /** Use a custom sender, for example a test recorder. */
        public Builder sender(EventSender sender) { this.sender = sender; return this; }

        public OmnirecClient build() {
            EventSender effective = sender;
            if (effective == null) {
                Objects.requireNonNull(endpoint, "endpoint");
                JdkHttpEventSender.Options options = JdkHttpEventSender.Options.defaults(endpoint, apiKey);
                effective = new JdkHttpEventSender(async ? options : options.synchronous());
            }
            EventValidator validator = new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE);
            return new OmnirecClient(new ServerEventEmitter(effective, validator, tenantId, validate), effective);
        }
    }
}
