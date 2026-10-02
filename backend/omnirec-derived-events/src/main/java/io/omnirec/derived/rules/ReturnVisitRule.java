// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.rules;

import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.derived.DerivedEventRule;
import io.omnirec.derived.DerivedEvents;
import io.omnirec.derived.RuleContext;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code return_visit}: a new session for a visitor whose previous session
 * started at least {@code minimumGap} earlier (default 30 minutes). The first
 * session ever seen for a visitor is not a return. Gaps are measured in event
 * time, so a delayed queue does not create false returns.
 */
public class ReturnVisitRule implements DerivedEventRule {

    /** How long a visitor's last session is remembered. */
    static final Duration MEMORY = Duration.ofDays(400);

    private final Duration minimumGap;

    public ReturnVisitRule(Duration minimumGap) {
        this.minimumGap = minimumGap;
    }

    @Override
    public String name() {
        return StandardEvents.RETURN_VISIT;
    }

    @Override
    public Set<String> subscribesTo() {
        return Set.of(StandardEvents.SESSION_STARTED);
    }

    @Override
    public void onEvent(CommerceEvent event, RuleContext context) {
        String key = "last:" + event.tenantId() + ":" + event.identity().anonymousId();
        String sessionId = event.identity().sessionId();
        Instant at = event.timestamp();

        String previous = context.get(key).orElse(null);
        String previousSession = null;
        Instant previousAt = null;
        if (previous != null) {
            int bar = previous.lastIndexOf('|');
            previousSession = previous.substring(0, bar);
            previousAt = Instant.ofEpochMilli(Long.parseLong(previous.substring(bar + 1)));
        }
        // A redelivery of the same session_started, or an older one arriving late, changes nothing.
        if (sessionId.equals(previousSession) || (previousAt != null && !at.isAfter(previousAt))) return;

        context.put(key, sessionId + "|" + at.toEpochMilli(), MEMORY);
        if (previousAt == null || Duration.between(previousAt, at).compareTo(minimumGap) < 0) return;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("previousSessionId", previousSession);
        properties.put("minutesSinceLastVisit", Duration.between(previousAt, at).toMinutes());
        context.emit(DerivedEvents.create(StandardEvents.RETURN_VISIT, event.tenantId(), sessionId,
                event.identity(), at, context.now(), Map.of(), properties));
    }
}
