package com.example.financialstream.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SanitiserTest {

    @Test
    void redactsSensitiveKeysAndEmbeddedCredentials() {
        assertEquals("******", Sanitiser.sanitise("database.password", "secret-value"));
        assertEquals("******", Sanitiser.sanitise("kafka.config", "username=alice password=secret"));
        assertEquals("visible", Sanitiser.sanitise("application.name", "visible"));
        assertNull(Sanitiser.sanitise("application.name", null));
    }

    @Test
    void sanitiseMapKeepsOnlyAllowedKeysAndRedactsValues() {
        Map<String, Object> raw = Map.of(
                "application.name", "payments",
                "api-key", "secret",
                "ignored", "not-exposed"
        );

        Map<String, String> result = Sanitiser.sanitiseMap(
                raw,
                List.of("application.name", "api-key", "missing")
        );

        assertEquals("payments", result.get("application.name"));
        assertEquals("******", result.get("api-key"));
        assertFalse(result.containsKey("ignored"));
        assertFalse(result.containsKey("missing"));
    }

    @Test
    void stripsCredentialsFromJdbcUrls() {
        assertEquals(
                "jdbc:postgresql://host:5432/payments",
                Sanitiser.sanitiseJdbcUrl("jdbc:postgresql://user:pass@host:5432/payments")
        );
        assertEquals(
                "jdbc:postgresql://host:5432/payments",
                Sanitiser.sanitiseJdbcUrl("jdbc:postgresql://host:5432/payments")
        );
        assertNull(Sanitiser.sanitiseJdbcUrl(null));
    }
}
