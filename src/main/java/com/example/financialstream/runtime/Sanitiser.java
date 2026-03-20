package com.example.financialstream.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strips sensitive values from configuration data before exposing via the runtime discovery API.
 * Uses a deny-list approach: any key containing a sensitive pattern gets its value redacted.
 */
public final class Sanitiser {

    private static final Set<String> SENSITIVE_PATTERNS = Set.of(
            "password", "secret", "credential",
            "apikey", "api-key", "api_key", "api.key",
            "sasl.jaas", "ssl.key.password", "ssl.keystore.password", "ssl.truststore.password",
            "token", "access-token", "access.token",
            "private-key", "private.key", "signing-key"
    );

    private static final String REDACTED = "******";

    private Sanitiser() {}

    /**
     * Sanitise a single key-value pair. Returns REDACTED if the key matches
     * a sensitive pattern or the value contains embedded credential patterns.
     */
    public static String sanitise(String key, String value) {
        if (value == null) return null;

        String lowerKey = key.toLowerCase(Locale.ROOT);
        for (String pattern : SENSITIVE_PATTERNS) {
            if (lowerKey.contains(pattern)) return REDACTED;
        }

        // Catch embedded credential patterns in values (e.g., JAAS config strings)
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (lowerValue.contains("password=") || lowerValue.contains("username=")
                || lowerValue.contains("api_key=") || lowerValue.contains("apikey=")
                || lowerValue.contains("org.apache.kafka.common.security")) {
            return REDACTED;
        }

        return value;
    }

    /**
     * Sanitise a map, only keeping keys present in the allowedKeys collection.
     * Whitelist approach: keys not in the list are silently dropped.
     */
    public static Map<String, String> sanitiseMap(Map<?, ?> raw, Collection<String> allowedKeys) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            Object val = raw.get(key);
            if (val != null) {
                result.put(key, sanitise(key, String.valueOf(val)));
            }
        }
        return result;
    }

    /**
     * Sanitise a JDBC URL: strip embedded username/password if present.
     * jdbc:postgresql://user:pass@host:5432/db → jdbc:postgresql://host:5432/db
     */
    public static String sanitiseJdbcUrl(String url) {
        if (url == null) return null;
        try {
            return url.replaceAll("//[^/@]+@", "//");
        } catch (Exception e) {
            return REDACTED;
        }
    }
}
