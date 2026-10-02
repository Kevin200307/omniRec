// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.outbox;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.commerce.model.CommerceEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code omnirec_outbox} table. PostgreSQL: inserts use
 * {@code ON CONFLICT DO NOTHING} and the relay claims rows with
 * {@code FOR UPDATE SKIP LOCKED}, so several application instances can relay
 * at once without sending a row twice.
 */
public class OutboxStore {

    /** One stored event. */
    public record Row(String eventId, CommerceEvent event, int attempts) {
    }

    private static final Pattern TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$");

    private final JdbcTemplate jdbc;
    private final String table;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public OutboxStore(JdbcTemplate jdbc, String table) {
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("not a valid outbox table name: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
    }

    public void createTableIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                + " event_id VARCHAR(256) PRIMARY KEY,"
                + " event_json TEXT NOT NULL,"
                + " created_at TIMESTAMP NOT NULL,"
                + " attempts INTEGER NOT NULL DEFAULT 0,"
                + " next_attempt_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + table.replace('.', '_') + "_due ON " + table + " (next_attempt_at)");
    }

    /** Joins the caller's transaction when there is one (JdbcTemplate uses the bound connection). */
    public void insert(CommerceEvent event, Instant now) {
        jdbc.update("INSERT INTO " + table + " (event_id, event_json, created_at, attempts, next_attempt_at)"
                        + " VALUES (?, ?, ?, 0, ?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId(), write(event), Timestamp.from(now), Timestamp.from(now));
    }

    /** Rows due now, locked for this transaction; rows locked by another relay are skipped. */
    public List<Row> claimDue(Instant now, int limit) {
        return jdbc.query("SELECT event_id, event_json, attempts FROM " + table
                        + " WHERE next_attempt_at <= ? ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Row(rs.getString("event_id"), read(rs.getString("event_json")), rs.getInt("attempts")),
                Timestamp.from(now), limit);
    }

    public void delete(List<String> eventIds) {
        for (String id : eventIds) {
            jdbc.update("DELETE FROM " + table + " WHERE event_id = ?", id);
        }
    }

    public void reschedule(String eventId, int attempts, Instant nextAttemptAt) {
        jdbc.update("UPDATE " + table + " SET attempts = ?, next_attempt_at = ? WHERE event_id = ?",
                attempts, Timestamp.from(nextAttemptAt), eventId);
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private String write(CommerceEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("event " + event.eventId() + " could not be serialised", e);
        }
    }

    private CommerceEvent read(String value) {
        try {
            return json.readValue(value, CommerceEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox row could not be read: " + e.getOriginalMessage(), e);
        }
    }
}
