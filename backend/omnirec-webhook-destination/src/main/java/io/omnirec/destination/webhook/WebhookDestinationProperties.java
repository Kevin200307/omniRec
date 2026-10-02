// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.webhook;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code omnirec.destinations.webhook}: where to POST events.
 *
 * <pre>
 * omnirec:
 *   destinations:
 *     webhook:
 *       enabled: true
 *       endpoints:
 *         crm:
 *           url: https://crm.example.com/hooks/omnirec
 *           secret: ${CRM_WEBHOOK_SECRET}
 *           events: [purchase_completed, cart_abandoned, refund_issued]
 *         warehouse:
 *           url: https://etl.example.com/omnirec
 *           secret: ${ETL_SECRET}
 *           events: ["*"]
 * </pre>
 */
public class WebhookDestinationProperties {

    private boolean enabled = false;
    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Endpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(Map<String, Endpoint> endpoints) { this.endpoints = endpoints; }

    public static class Endpoint {
        /** Receiver URL. HTTPS, except for localhost. */
        private String url;
        /** Signs every request; the receiver checks {@code X-Omnirec-Signature} with it. */
        private String secret;
        /**
         * Event names to send. {@code *} sends everything; {@code cart_*} every
         * name with that prefix. Aliases are not matched: events are canonical
         * by the time they get here.
         */
        private List<String> events = List.of();
        /** Only these tenants' events. Empty: every tenant. */
        private List<String> tenants = List.of();
        /** Also send events outside the catalog and tracking plan (flagged unplanned). */
        private boolean includeUnplanned = false;
        private Duration timeout = Duration.ofSeconds(10);
        /** Extra request headers, for example an authorization token the receiver expects. */
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public List<String> getEvents() { return events; }
        public void setEvents(List<String> events) { this.events = events; }
        public List<String> getTenants() { return tenants; }
        public void setTenants(List<String> tenants) { this.tenants = tenants; }
        public boolean isIncludeUnplanned() { return includeUnplanned; }
        public void setIncludeUnplanned(boolean includeUnplanned) { this.includeUnplanned = includeUnplanned; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    }
}
