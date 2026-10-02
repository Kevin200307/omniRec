// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.eventapi.tenant.JdbcTenantRegistry;
import io.omnirec.eventapi.tenant.KeyHash;
import io.omnirec.eventapi.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The database-backed tenant registry against a real PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTenantRegistryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A clock the test moves forward to cross the refresh interval. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private JdbcTenantRegistry registry() {
        return new JdbcTenantRegistry(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                "omnirec_tenant", Duration.ofSeconds(30), ValidationMode.PERMISSIVE, clock);
    }

    private void sql(String statement, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement p = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            p.executeUpdate();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        registry(); // creates the table if missing
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM omnirec_tenant");
        }
    }

    @Test
    void resolvesHashedKeysAndTenantSettings() throws SQLException {
        sql("INSERT INTO omnirec_tenant (tenant_id, publishable_key_sha256, secret_key_sha256, allowed_origins,"
                        + " validation_mode, plan_paths) VALUES (?, ?, ?, ?, ?, ?)",
                "store-1", KeyHash.of("pk_one"), KeyHash.of("sk_one"), "https://one.example, https://www.one.example",
                "strict", "classpath:plans/store-a.plan.yaml");

        JdbcTenantRegistry registry = registry();

        assertEquals("store-1", registry.tenantForPublishableKey("pk_one").orElseThrow());
        assertEquals("store-1", registry.tenantForSecretKey("sk_one").orElseThrow());
        assertTrue(registry.tenantForPublishableKey("sk_one").isEmpty());
        assertTrue(registry.tenantForPublishableKey("pk_wrong").isEmpty());
        Tenant tenant = registry.find("store-1").orElseThrow();
        assertEquals(List.of("https://one.example", "https://www.one.example"), tenant.allowedOrigins());
        assertEquals(ValidationMode.STRICT, tenant.validationMode());
        assertEquals(List.of("classpath:plans/store-a.plan.yaml"), tenant.planPaths());
        assertTrue(registry.hasAnyPublishableKey());
    }

    @Test
    void picksUpKeyRotationAfterTheRefreshInterval() throws SQLException {
        sql("INSERT INTO omnirec_tenant (tenant_id, publishable_key_sha256) VALUES (?, ?)", "store-2", KeyHash.of("pk_old"));
        JdbcTenantRegistry registry = registry();
        assertTrue(registry.tenantForPublishableKey("pk_old").isPresent());

        sql("UPDATE omnirec_tenant SET publishable_key_sha256 = ? WHERE tenant_id = ?", KeyHash.of("pk_new"), "store-2");
        assertTrue(registry.tenantForPublishableKey("pk_old").isPresent(), "cached until the interval passes");

        clock.now = clock.now.plusSeconds(31);
        assertTrue(registry.tenantForPublishableKey("pk_old").isEmpty());
        assertEquals("store-2", registry.tenantForPublishableKey("pk_new").orElseThrow());
    }

    @Test
    void aDisabledTenantsKeysStopWorking() throws SQLException {
        sql("INSERT INTO omnirec_tenant (tenant_id, publishable_key_sha256, enabled) VALUES (?, ?, false)",
                "store-3", KeyHash.of("pk_three"));
        JdbcTenantRegistry registry = registry();
        assertTrue(registry.tenantForPublishableKey("pk_three").isEmpty());
        assertFalse(registry.find("store-3").orElseThrow().enabled());
        assertFalse(registry.hasAnyPublishableKey());
    }

    @Test
    void storesOnlyHashes() {
        assertEquals(64, KeyHash.of("pk_anything").length());
        assertNotEquals("pk_anything", KeyHash.of("pk_anything"));
    }

    @Test
    void refusesAnUnsafeTableName() {
        assertThrows(IllegalStateException.class, () -> new JdbcTenantRegistry(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "tenants; DROP TABLE x", Duration.ofSeconds(1),
                ValidationMode.STRICT, clock));
    }
}
