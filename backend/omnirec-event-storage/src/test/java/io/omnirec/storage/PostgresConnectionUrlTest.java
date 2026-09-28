// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.storage.jdbc.PostgresConnectionUrl;
import io.omnirec.storage.jdbc.PostgresConnectionUrl.Resolved;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresConnectionUrlTest {

    @Test
    void aJdbcUrlIsUsedAsIs() {
        Resolved resolved = PostgresConnectionUrl.resolve("jdbc:postgresql://localhost:5432/omnirec", "omnirec", "pw");

        assertEquals("jdbc:postgresql://localhost:5432/omnirec", resolved.jdbcUrl());
        assertEquals("omnirec", resolved.username());
        assertEquals("pw", resolved.password());
        assertEquals("localhost", resolved.host());
    }

    /** The form Neon's console hands out. Nothing about it is Neon-specific. */
    @Test
    void aHostedLibpqUrlIsConvertedAndItsCredentialsLiftedOut() {
        Resolved resolved = PostgresConnectionUrl.resolve(
                "postgresql://app_user:s%40cret@ep-cool-bird-123.eu-central-1.aws.neon.tech/omnirec?sslmode=require",
                null, null);

        assertEquals("jdbc:postgresql://ep-cool-bird-123.eu-central-1.aws.neon.tech/omnirec?sslmode=require",
                resolved.jdbcUrl());
        assertEquals("app_user", resolved.username());
        assertEquals("s@cret", resolved.password(), "percent-encoded credentials must be decoded");
        assertFalse(resolved.jdbcUrl().contains("s%40cret"), "the password must not stay in the URL");
    }

    @Test
    void explicitCredentialsWinOverEmbeddedOnes() {
        Resolved resolved = PostgresConnectionUrl.resolve("postgres://embedded:one@db.example.com:6543/x", "explicit", "two");

        assertEquals("jdbc:postgresql://db.example.com:6543/x", resolved.jdbcUrl());
        assertEquals("explicit", resolved.username());
        assertEquals("two", resolved.password());
    }

    @Test
    void neverPrintsThePassword() {
        Resolved resolved = PostgresConnectionUrl.resolve("postgresql://u:topsecret@h/db", null, null);

        assertFalse(resolved.toString().contains("topsecret"));
    }

    @Test
    void refusesAMissingOrForeignUrlWithoutEchoingIt() {
        assertThrows(IllegalStateException.class, () -> PostgresConnectionUrl.resolve(null, null, null));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PostgresConnectionUrl.resolve("mysql://u:topsecret@h/db", null, null));
        assertFalse(e.getMessage().contains("topsecret"));
    }
}
