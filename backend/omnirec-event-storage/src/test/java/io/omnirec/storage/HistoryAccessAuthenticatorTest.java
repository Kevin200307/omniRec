// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.eventapi.config.EventApiProperties;
import io.omnirec.storage.api.HistoryAccessAuthenticator;
import io.omnirec.storage.config.EventStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HistoryAccessAuthenticatorTest {

    private EventApiProperties events;
    private EventStorageProperties.HistoryApi historyApi;

    @BeforeEach
    void setUp() {
        events = new EventApiProperties();
        historyApi = new EventStorageProperties.HistoryApi();
        tenant("tenant_A", "pk_a", "sk_a_secret");
        tenant("tenant_B", "pk_b", "sk_b_secret");
        tenant("tenant_C", "pk_c", null);
    }

    private EventApiProperties.Tenant tenant(String id, String apiKey, String secretKey) {
        EventApiProperties.Tenant tenant = new EventApiProperties.Tenant();
        tenant.setApiKey(apiKey);
        tenant.setSecretKey(secretKey);
        events.getTenants().put(id, tenant);
        return tenant;
    }

    private void platformKey(String name, String key, String... tenants) {
        EventStorageProperties.PlatformKey platformKey = new EventStorageProperties.PlatformKey();
        platformKey.setKey(key);
        platformKey.setTenants(Set.of(tenants));
        historyApi.getPlatformKeys().put(name, platformKey);
    }

    private HistoryAccessAuthenticator authenticator() {
        return new HistoryAccessAuthenticator(events, historyApi);
    }

    @Test
    void aTenantSecretKeyReadsExactlyItsOwnTenant() {
        assertEquals(Set.of("tenant_A"), authenticator().authenticate("sk_a_secret").orElseThrow().tenants());
        assertEquals(Set.of("tenant_B"), authenticator().authenticate("sk_b_secret").orElseThrow().tenants());
    }

    /** The publishable key is in every storefront's page source. It must never read. */
    @Test
    void aPublishableKeyReadsNothing() {
        assertTrue(authenticator().authenticate("pk_a").isEmpty());
    }

    @Test
    void anUnknownOrMissingKeyReadsNothing() {
        assertTrue(authenticator().authenticate("sk_guess").isEmpty());
        assertTrue(authenticator().authenticate(null).isEmpty());
        assertTrue(authenticator().authenticate("  ").isEmpty());
    }

    @Test
    void aDisabledTenantsSecretKeyReadsNothing() {
        events.getTenants().get("tenant_A").setEnabled(false);

        assertTrue(authenticator().authenticate("sk_a_secret").isEmpty());
    }

    @Test
    void aPlatformKeyReadsItsConfiguredTenantsOnly() {
        platformKey("ops", "sk_platform", "tenant_A", "tenant_C");

        assertEquals(Set.of("tenant_A", "tenant_C"), authenticator().authenticate("sk_platform").orElseThrow().tenants());
    }

    @Test
    void aPlatformKeyLosesADisabledTenant() {
        platformKey("ops", "sk_platform", "tenant_A", "tenant_B");
        events.getTenants().get("tenant_B").setEnabled(false);

        assertEquals(Set.of("tenant_A"), authenticator().authenticate("sk_platform").orElseThrow().tenants());
    }

    @Test
    void refusesToStartWithASecretKeyEqualToAPublishableKey() {
        tenant("tenant_D", "pk_d", "pk_a");

        IllegalStateException e = assertThrows(IllegalStateException.class, this::authenticator);
        assertFalse(e.getMessage().contains("pk_a"), "the error must not print the key");
    }

    @Test
    void refusesToStartWithTheSameSecretForTwoTenants() {
        tenant("tenant_D", "pk_d", "sk_a_secret");

        assertThrows(IllegalStateException.class, this::authenticator);
    }

    @Test
    void refusesToStartWithAPlatformKeyNamingAnUnknownTenant() {
        platformKey("ops", "sk_platform", "tenant_A", "tenant_typo");

        assertThrows(IllegalStateException.class, this::authenticator);
    }
}
