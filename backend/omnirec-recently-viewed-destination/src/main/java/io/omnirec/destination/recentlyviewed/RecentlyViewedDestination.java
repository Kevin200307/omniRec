package io.omnirec.destination.recentlyviewed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;

/**
 * Keeps each signed-in user's recently-viewed list current from
 * {@code product_viewed} events.
 *
 * The list is written in exactly the shape the serving side reads through
 * {@code RedisCacheProvider.getList}: a Redis list at
 * {@code recently-viewed:<userId>}, newest first, each element the product id
 * JSON-encoded.
 *
 * A plain move-to-front would be wrong here. Retries deliver events out of
 * order, so an older view arriving late would jump ahead of a newer one. A
 * sorted set beside the list records when each product was last viewed; a view
 * no newer than the recorded one changes nothing, and the list is rebuilt from
 * the set. Both happen in one script, so the write is atomic, idempotent under
 * redelivery, and independent of arrival order.
 *
 * Anonymous views are skipped, as they were on the legacy path: the serving
 * endpoint looks lists up by userId.
 */
public class RecentlyViewedDestination implements EventDestination {

    public static final String ID = "recently-viewed";

    /** The serving side's key prefix, from PersonalizationService. */
    public static final String LIST_PREFIX = "recently-viewed:";

    /** The ordering index beside each list. */
    public static final String INDEX_SUFFIX = ":viewed-at";

    /*
     * KEYS[1] list, KEYS[2] index.
     * ARGV[1] product (JSON), ARGV[2] viewed-at millis, ARGV[3] max items, ARGV[4] ttl millis.
     * Returns 1 if the list changed, 0 for a stale or repeated view.
     */
    private static final String RECORD_VIEW = """
            local current = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if current and tonumber(current) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            redis.call('ZREMRANGEBYRANK', KEYS[2], 0, -(tonumber(ARGV[3]) + 1))
            local newestFirst = redis.call('ZREVRANGE', KEYS[2], 0, -1)
            redis.call('DEL', KEYS[1])
            if #newestFirst > 0 then
              redis.call('RPUSH', KEYS[1], unpack(newestFirst))
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return 1
            """;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(RECORD_VIEW, Long.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final RecentlyViewedProperties properties;

    public RecentlyViewedDestination(StringRedisTemplate redis, RecentlyViewedProperties properties) {
        if (properties.getMaxItems() < 1) {
            throw new IllegalArgumentException("omnirec.destinations.recently-viewed.max-items must be at least 1");
        }
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(CommerceEvent event) {
        return event.eventType() == EventType.PRODUCT_VIEWED
                && !event.isEngagementUpdate()
                && event.identity().isAuthenticated()
                && event.commerce().productId() != null
                && (properties.getTenantId() == null || properties.getTenantId().equals(event.tenantId()));
    }

    @Override
    public void send(CommerceEvent event) {
        if (!supports(event)) {
            return;
        }
        String list = LIST_PREFIX + event.identity().userId();
        Instant viewedAt = event.timestamp() != null ? event.timestamp() : event.receivedAt();
        try {
            redis.execute(SCRIPT, List.of(list, list + INDEX_SUFFIX),
                    encode(event.commerce().productId()),
                    Long.toString(viewedAt == null ? System.currentTimeMillis() : viewedAt.toEpochMilli()),
                    Integer.toString(properties.getMaxItems()),
                    Long.toString(properties.getTtl().toMillis()));
        } catch (RuntimeException e) {
            // Thrown, not swallowed: the consumer retries and eventually dead-letters.
            throw new DestinationException(ID, "Could not update recently-viewed for event " + event.eventId(), e);
        }
    }

    /** The same encoding RedisCacheProvider.pushCapped uses, so its getList decodes it. */
    private static String encode(String productId) {
        try {
            return JSON.writeValueAsString(productId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
