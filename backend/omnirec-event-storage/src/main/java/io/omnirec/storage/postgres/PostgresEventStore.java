// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.postgres;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.compat.V1Compat;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventCursor;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.commerce.storage.EventStoreException;
import io.omnirec.storage.jdbc.SqlFailures;
import io.omnirec.storage.jdbc.StorageDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link EventStore} on any PostgreSQL server.
 *
 * It does not know or care where that server runs — localhost, Neon, RDS,
 * Supabase. Every PostgreSQL-compatible feature it uses ({@code JSONB},
 * {@code ON CONFLICT}, row-value comparison) has been standard since 9.5.
 *
 * <h2>Writes</h2>
 * One transaction per event: the event row, plus the identity link when the
 * event names both an anonymous id and a user id. Either both land or neither
 * does, so a retry never finds half an event. The insert is
 * {@code ON CONFLICT DO NOTHING}: a redelivered event is a zero-row insert,
 * reported as {@link SaveOutcome#DUPLICATE}.
 *
 * <h2>Customer history</h2>
 * Two keyset scans, each answered in order from its own index, merged:
 * <pre>
 *   events captured as the customer          (tenant_id, user_id, occurred_at, event_id)
 *   UNION ALL
 *   anonymous events of linked devices        (tenant_id, anonymous_id, occurred_at, event_id)
 *                                              WHERE user_id IS NULL
 * </pre>
 * The two halves are disjoint ({@code user_id = ?} vs {@code user_id IS NULL}),
 * so the merge never produces a duplicate. Rows are read, never modified: an
 * anonymous event keeps its null user id forever, and the link is what makes
 * it part of the customer's journey.
 */
public class PostgresEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresEventStore.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final String COLUMNS = "tenant_id, event_id, event_type, schema_version, occurred_at, "
            + "received_at, anonymous_id, user_id, session_id, commerce, properties, context, "
            + "event_version, kind, source, data";

    protected final DataSource dataSource;
    protected final String schema;
    private final ObjectMapper json;

    private final String insertEventSql;
    private final String upsertLinkSql;

    public PostgresEventStore(StorageDatabase database) {
        this(database.dataSource(), database.schema());
    }

    public PostgresEventStore(DataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.schema = StorageDatabase.validSchema(schema);
        this.json = storageObjectMapper();

        this.insertEventSql = "INSERT INTO " + table("commerce_events")
                + " (tenant_id, event_id, event_type, schema_version, occurred_at, received_at,"
                + " anonymous_id, user_id, session_id, product_id, commerce, properties, context,"
                + " event_version, kind, source, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),"
                + " ?, ?, ?, CAST(? AS jsonb))"
                + " ON CONFLICT DO NOTHING";

        // Most recent assertion wins, by event time rather than arrival time:
        // a retry that delivers an old login late must not undo a newer one.
        // linked_at is the latest event time at which the current mapping was
        // asserted; first_seen_at the earliest at which any was.
        this.upsertLinkSql = "INSERT INTO " + table("identity_links") + " AS l"
                + " (tenant_id, anonymous_id, user_id, first_seen_at, linked_at)"
                + " VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, anonymous_id) DO UPDATE SET"
                + "   user_id = CASE WHEN EXCLUDED.linked_at > l.linked_at THEN EXCLUDED.user_id ELSE l.user_id END,"
                + "   linked_at = GREATEST(l.linked_at, EXCLUDED.linked_at),"
                + "   first_seen_at = LEAST(l.first_seen_at, EXCLUDED.first_seen_at)"
                // Skip the row write entirely in the common case: the same link, restated.
                + " WHERE EXCLUDED.linked_at > l.linked_at OR EXCLUDED.first_seen_at < l.first_seen_at";
    }

    /**
     * The on-disk JSON format, owned here rather than borrowed from the
     * application's ObjectMapper, so that reconfiguring the web layer can never
     * change how history is written. Unknown fields are ignored on read, so rows
     * written by a newer version remain readable.
     */
    static ObjectMapper storageObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Stored money is read back with its exact scale.
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    protected String table(String name) {
        return schema + "." + name;
    }

    // ------------------------------------------------------------------ writes

    @Override
    public SaveOutcome save(CommerceEvent event) {
        if (isBlank(event.tenantId())) {
            // Every event through the Event API has a tenant. One without is a
            // bug upstream, and storing it would put it outside every tenant's
            // isolation boundary. Refuse it permanently.
            throw new EventStoreException("event " + event.eventId() + " has no tenantId", null, false);
        }

        String commerce;
        String properties;
        String context;
        String data;
        try {
            // The v1 view is still written for one release so v1 readers keep working.
            @SuppressWarnings("deprecation")
            CommerceData v1View = event.commerce();
            commerce = json.writeValueAsString(v1View);
            data = json.writeValueAsString(event.data());
            properties = json.writeValueAsString(event.properties());
            context = json.writeValueAsString(event.context());
        } catch (JsonProcessingException e) {
            throw new EventStoreException("event " + event.eventId() + " could not be serialised", e, false);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement insert = connection.prepareStatement(insertEventSql)) {
                    EventIdentity identity = event.identity();
                    insert.setString(1, event.tenantId());
                    insert.setString(2, event.eventId());
                    insert.setString(3, event.eventType().wireName());
                    insert.setString(4, event.schemaVersion());
                    insert.setObject(5, timestamp(event.timestamp()));
                    insert.setObject(6, timestamp(event.receivedAt()));
                    insert.setString(7, blankToNull(identity.anonymousId()));
                    insert.setString(8, blankToNull(identity.userId()));
                    insert.setString(9, blankToNull(identity.sessionId()));
                    insert.setString(10, blankToNull(event.data().product().id()));
                    insert.setString(11, commerce);
                    insert.setString(12, properties);
                    insert.setString(13, context);
                    insert.setInt(14, event.eventVersion());
                    insert.setString(15, event.kind());
                    insert.setString(16, event.source() == null ? null : event.source().wireName());
                    insert.setString(17, data);
                    inserted = insert.executeUpdate();
                }

                // Recorded on every save, duplicates included: it is idempotent,
                // and it repairs a link if an earlier attempt was rolled back.
                recordIdentityLink(connection, event);

                connection.commit();
                return inserted == 1 ? SaveOutcome.STORED : SaveOutcome.DUPLICATE;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw SqlFailures.translate("could not store event " + event.eventId(), e);
        }
    }

    /**
     * Mirrors IdentityResolver.recordLinkIfPresent: any event naming both ids
     * links them — an {@code identify}, a {@code user_logged_in}, or an event
     * the Event API already resolved to a user.
     */
    private void recordIdentityLink(Connection connection, CommerceEvent event) throws SQLException {
        String anonymousId = event.identity().anonymousId();
        String userId = event.identity().userId();
        if (isBlank(anonymousId) || isBlank(userId)) {
            return;
        }
        OffsetDateTime at = timestamp(event.timestamp() != null ? event.timestamp() : event.receivedAt());
        try (PreparedStatement upsert = connection.prepareStatement(upsertLinkSql)) {
            upsert.setString(1, event.tenantId());
            upsert.setString(2, anonymousId);
            upsert.setString(3, userId);
            upsert.setObject(4, at);
            upsert.setObject(5, at);
            upsert.executeUpdate();
        }
    }

    // ------------------------------------------------------------------- reads

    @Override
    public CustomerEventPage findCustomerEvents(String tenantId, String customerId, EventQuery query) {
        if (isBlank(tenantId) || isBlank(customerId)) {
            throw new IllegalArgumentException("tenantId and customerId are required");
        }
        int fetch = query.limit() + 1;   // one extra row says whether there is a next page

        Sql sql = new Sql();
        sql.append("SELECT " + COLUMNS + " FROM (");

        sql.append("(SELECT " + COLUMNS + " FROM " + table("commerce_events"))
                .append(" WHERE tenant_id = ?", tenantId)
                .append(" AND user_id = ?", customerId);
        appendFilters(sql, query);
        sql.append(" ORDER BY occurred_at DESC, event_id DESC LIMIT ?)", fetch);

        sql.append(" UNION ALL ");

        sql.append("(SELECT " + COLUMNS + " FROM " + table("commerce_events"))
                .append(" WHERE tenant_id = ?", tenantId)
                .append(" AND user_id IS NULL")
                .append(" AND anonymous_id IN (SELECT anonymous_id FROM " + table("identity_links")
                        + " WHERE tenant_id = ?", tenantId)
                .append(" AND user_id = ?)", customerId);
        appendFilters(sql, query);
        sql.append(" ORDER BY occurred_at DESC, event_id DESC LIMIT ?)", fetch);

        sql.append(") history ORDER BY occurred_at DESC, event_id DESC LIMIT ?", fetch);

        List<Row> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = sql.prepare(connection);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(readRow(rs));
            }
        } catch (SQLException e) {
            throw SqlFailures.translate("could not read customer history", e);
        }

        boolean hasMore = rows.size() > query.limit();
        List<Row> page = hasMore ? rows.subList(0, query.limit()) : rows;

        List<CommerceEvent> events = new ArrayList<>(page.size());
        for (Row row : page) {
            if (row.event() != null) {
                events.add(row.event());
            }
        }
        // From the last row read, not the last event returned, so a skipped
        // unreadable row can never make the cursor stall or go backwards.
        EventCursor next = hasMore
                ? new EventCursor(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).eventId())
                : null;
        return new CustomerEventPage(customerId, events, next);
    }

    private static void appendFilters(Sql sql, EventQuery query) {
        if (query.after() != null) {
            // Row comparison: strictly after (older than) the cursor in
            // (occurred_at DESC, event_id DESC) order. Answered by the index.
            sql.append(" AND (occurred_at, event_id) < (?", timestamp(query.after().occurredAt()))
                    .append(", ?)", query.after().eventId());
        }
        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?", timestamp(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at < ?", timestamp(query.to()));
        }
        if (!query.eventTypes().isEmpty()) {
            sql.append(" AND event_type = ANY (?)", query.eventTypes().stream()
                    .map(EventName::wireName).sorted().toArray(String[]::new));
        }
    }

    private record Row(Instant occurredAt, String eventId, CommerceEvent event) {
    }

    private Row readRow(ResultSet rs) throws SQLException {
        String eventId = rs.getString("event_id");
        Instant occurredAt = rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
        String typeName = rs.getString("event_type");

        EventName type = EventName.parse(typeName).orElse(null);
        if (type == null) {
            // Not a well-formed event name, so it cannot have been written by
            // this pipeline. Skip it rather than fail the whole page. Unknown but
            // well-formed names (custom events, newer catalogs) are read normally.
            log.warn("Skipping stored event {} with unknown event type '{}'", eventId, typeName);
            return new Row(occurredAt, eventId, null);
        }

        OffsetDateTime receivedAt = rs.getObject("received_at", OffsetDateTime.class);
        // schema_version records what the row was written as; events are always read as v2.
        try {
            CommerceEvent event = CommerceEvent.builder()
                    .eventId(eventId)
                    .eventType(type)
                    .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                    .timestamp(occurredAt)
                    .tenantId(rs.getString("tenant_id"))
                    .identity(new EventIdentity(
                            rs.getString("anonymous_id"), rs.getString("user_id"), rs.getString("session_id")))
                    .eventVersion(rs.getInt("event_version"))
                    .kind(rs.getString("kind"))
                    .source(EventSource.fromWireName(rs.getString("source")))
                    .data(readData(rs))
                    .properties(json.readValue(rs.getString("properties"), MAP))
                    .context(json.readValue(rs.getString("context"), EventContext.class))
                    .receivedAt(receivedAt == null ? null : receivedAt.toInstant())
                    .build();
            return new Row(occurredAt, eventId, event);
        } catch (JsonProcessingException e) {
            log.warn("Skipping stored event {}: its JSON columns could not be read ({})", eventId,
                    e.getOriginalMessage());
            return new Row(occurredAt, eventId, null);
        }
    }

    // --------------------------------------------------------------- retention

    /**
     * Deletes events that occurred before {@code cutoff}, {@code batchSize}
     * rows per statement and committing each, so a large purge never holds a
     * long lock or a huge transaction. Identity links are kept: they are small,
     * and a link outliving its events is harmless.
     *
     * @return rows deleted
     */
    public long deleteEventsOccurredBefore(Instant cutoff, int batchSize) {
        return deleteEventsOccurredBefore(cutoff, batchSize, null, List.of());
    }

    /**
     * As {@link #deleteEventsOccurredBefore(Instant, int)}, for one tenant
     * ({@code tenantId}), or for every tenant except {@code exceptTenants}
     * ({@code tenantId} null): how per-tenant retention coexists with the
     * global one.
     */
    public long deleteEventsOccurredBefore(Instant cutoff, int batchSize, String tenantId,
                                           java.util.Collection<String> exceptTenants) {
        String scope = tenantId != null ? " AND tenant_id = ?" : exceptTenants.isEmpty() ? "" : " AND tenant_id <> ALL (?)";
        String sql = "DELETE FROM " + table("commerce_events") + " WHERE ctid IN ("
                + "SELECT ctid FROM " + table("commerce_events") + " WHERE occurred_at < ?" + scope + " LIMIT ?)";
        long total = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            java.sql.Array excluded = tenantId == null && !exceptTenants.isEmpty()
                    ? connection.createArrayOf("text", exceptTenants.toArray(new String[0])) : null;
            int deleted;
            do {
                int i = 1;
                delete.setObject(i++, timestamp(cutoff));
                if (tenantId != null) delete.setString(i++, tenantId);
                else if (excluded != null) delete.setArray(i++, excluded);
                delete.setInt(i, batchSize);
                deleted = delete.executeUpdate();
                total += deleted;
            } while (deleted >= batchSize);
        } catch (SQLException e) {
            throw SqlFailures.translate("could not purge expired events", e);
        }
        return total;
    }

    // ----------------------------------------------------------------- erasure

    @Override
    public List<String> anonymousIdsOf(String tenantId, String customerId) {
        String sql = "SELECT anonymous_id FROM " + table("identity_links") + " WHERE tenant_id = ? AND user_id = ?"
                + " UNION SELECT DISTINCT anonymous_id FROM " + table("commerce_events")
                + " WHERE tenant_id = ? AND user_id = ? AND anonymous_id IS NOT NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, customerId);
            statement.setString(3, tenantId);
            statement.setString(4, customerId);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            return ids;
        } catch (SQLException e) {
            throw SqlFailures.translate("could not look up the customer's devices", e);
        }
    }

    /**
     * One transaction: the customer's events, the anonymous events of their
     * devices, and every identity link touching either. A device shared with
     * someone else loses its anonymous history too; deleting too much is the
     * safe side of an erasure request.
     */
    @Override
    public CustomerErasure eraseCustomer(String tenantId, String customerId, java.util.Collection<String> anonymousIds) {
        String[] devices = anonymousIds.toArray(new String[0]);
        String deleteEvents = "DELETE FROM " + table("commerce_events")
                + " WHERE tenant_id = ? AND (user_id = ? OR anonymous_id = ANY (?))";
        String deleteLinks = "DELETE FROM " + table("identity_links")
                + " WHERE tenant_id = ? AND (user_id = ? OR anonymous_id = ANY (?))";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement events = connection.prepareStatement(deleteEvents);
                 PreparedStatement links = connection.prepareStatement(deleteLinks)) {
                java.sql.Array array = connection.createArrayOf("text", devices);
                for (PreparedStatement statement : List.of(events, links)) {
                    statement.setString(1, tenantId);
                    statement.setString(2, customerId);
                    statement.setArray(3, array);
                }
                long eventsDeleted = events.executeUpdate();
                long linksDeleted = links.executeUpdate();
                connection.commit();
                return new CustomerErasure(eventsDeleted, linksDeleted, List.copyOf(anonymousIds));
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw SqlFailures.translate("could not delete the customer's history", e);
        }
    }

    // ----------------------------------------------------------------- helpers

    protected static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("Rollback failed after a storage error", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    /** SQL text and its bind values, kept in step as clauses are appended. */
    private static final class Sql {
        private final StringBuilder text = new StringBuilder();
        private final List<Object> binds = new ArrayList<>();

        Sql append(String fragment) {
            text.append(fragment);
            return this;
        }

        Sql append(String fragment, Object bind) {
            text.append(fragment);
            binds.add(bind);
            return this;
        }

        PreparedStatement prepare(Connection connection) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(text.toString());
            try {
                for (int i = 0; i < binds.size(); i++) {
                    Object bind = binds.get(i);
                    if (bind instanceof String[] array) {
                        statement.setArray(i + 1, connection.createArrayOf("text", array));
                    } else {
                        statement.setObject(i + 1, bind);
                    }
                }
                return statement;
            } catch (SQLException e) {
                statement.close();
                throw e;
            }
        }
    }

    /** v2 rows carry data; rows written before migration V5 only have the v1 commerce column. */
    private EventData readData(ResultSet rs) throws SQLException, JsonProcessingException {
        EventData data = json.readValue(rs.getString("data"), EventData.class);
        if (!data.isEmpty()) return data;
        return V1Compat.toData(json.readValue(rs.getString("commerce"), CommerceData.class));
    }
}
