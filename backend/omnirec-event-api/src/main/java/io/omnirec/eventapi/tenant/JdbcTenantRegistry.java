// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import io.omnirec.commerce.validation.ValidationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tenants from a database table, for operators running many stores on one
 * collector. Tenants can be added, disabled and given new keys without a
 * restart; changes are picked up within the refresh interval.
 *
 * <pre>
 *   CREATE TABLE omnirec_tenant (
 *     tenant_id              TEXT PRIMARY KEY,
 *     publishable_key_sha256 TEXT UNIQUE,
 *     secret_key_sha256      TEXT UNIQUE,
 *     enabled                BOOLEAN NOT NULL DEFAULT TRUE,
 *     allowed_origins        TEXT,   -- comma separated
 *     validation_mode        TEXT,   -- strict | permissive
 *     plan_paths             TEXT    -- comma separated resource locations
 *   );
 * </pre>
 *
 * Only SHA-256 hashes of keys are stored. A presented key is hashed and looked
 * up; the hash comparison does not leak how close a guess was, because guessing
 * a hash prefix gives no information about the key.
 *
 * The table is created if missing. A failed refresh keeps the last good
 * snapshot, so a database blip does not lock every store out.
 */
public class JdbcTenantRegistry implements TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantRegistry.class);
    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$");

    private final String url;
    private final String username;
    private final String password;
    private final String table;
    private final Duration refreshInterval;
    private final ValidationMode defaultMode;
    private final Clock clock;

    private volatile Snapshot snapshot;

    private record Snapshot(Instant loadedAt, Map<String, Tenant> tenants, Map<String, String> byPublishableHash,
                            Map<String, String> bySecretHash) {
    }

    public JdbcTenantRegistry(String url, String username, String password, String table, Duration refreshInterval,
                              ValidationMode defaultMode, Clock clock) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("omnirec.events.tenant-source=jdbc needs omnirec.events.jdbc.url");
        }
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalStateException("omnirec.events.jdbc.table is not a valid table name: " + table);
        }
        this.url = url;
        this.username = username;
        this.password = password;
        this.table = table;
        this.refreshInterval = refreshInterval;
        this.defaultMode = defaultMode;
        this.clock = clock;
        createTableIfMissing();
        this.snapshot = load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void createTableIfMissing() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " tenant_id TEXT PRIMARY KEY,"
                    + " publishable_key_sha256 TEXT UNIQUE,"
                    + " secret_key_sha256 TEXT UNIQUE,"
                    + " enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + " allowed_origins TEXT,"
                    + " validation_mode TEXT,"
                    + " plan_paths TEXT)");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create tenant table " + table, e);
        }
    }

    private Snapshot load() {
        Map<String, Tenant> tenants = new LinkedHashMap<>();
        Map<String, String> publishable = new LinkedHashMap<>();
        Map<String, String> secret = new LinkedHashMap<>();
        String sql = "SELECT tenant_id, publishable_key_sha256, secret_key_sha256, enabled, allowed_origins,"
                + " validation_mode, plan_paths FROM " + table + " ORDER BY tenant_id";
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("tenant_id");
                boolean enabled = rs.getBoolean("enabled");
                String mode = rs.getString("validation_mode");
                tenants.put(id, new Tenant(id, enabled, split(rs.getString("allowed_origins")),
                        mode == null ? defaultMode : ValidationMode.valueOf(mode.trim().toUpperCase(Locale.ROOT)),
                        split(rs.getString("plan_paths"))));
                if (!enabled) continue;
                String publishableHash = rs.getString("publishable_key_sha256");
                String secretHash = rs.getString("secret_key_sha256");
                if (publishableHash != null) publishable.put(publishableHash.toLowerCase(Locale.ROOT), id);
                if (secretHash != null) secret.put(secretHash.toLowerCase(Locale.ROOT), id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read tenants from " + table, e);
        }
        return new Snapshot(clock.instant(), Map.copyOf(tenants), Map.copyOf(publishable), Map.copyOf(secret));
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Snapshot current() {
        Snapshot local = snapshot;
        if (clock.instant().isBefore(local.loadedAt().plus(refreshInterval))) return local;
        synchronized (this) {
            local = snapshot;
            if (clock.instant().isBefore(local.loadedAt().plus(refreshInterval))) return local;
            try {
                snapshot = local = load();
            } catch (RuntimeException e) {
                log.warn("Tenant refresh from {} failed; keeping the previous snapshot: {}", table, e.getMessage());
                snapshot = local = new Snapshot(clock.instant(), local.tenants(), local.byPublishableHash(),
                        local.bySecretHash());
            }
            return local;
        }
    }

    /** Forces the next lookup to reload, for tests and admin tooling. */
    public void refreshNow() {
        snapshot = load();
    }

    @Override
    public Optional<Tenant> find(String tenantId) {
        return Optional.ofNullable(tenantId).map(current().tenants()::get);
    }

    @Override
    public Optional<String> tenantForPublishableKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(current().byPublishableHash().get(KeyHash.of(key)));
    }

    @Override
    public Optional<String> tenantForSecretKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(current().bySecretHash().get(KeyHash.of(key)));
    }

    @Override
    public Collection<Tenant> tenants() {
        return current().tenants().values();
    }

    @Override
    public boolean hasAnyPublishableKey() {
        return !current().byPublishableHash().isEmpty();
    }
}
