package com.example.financialstream.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaStreamsConfigTest {

    @Test
    void rejectsUpgradeFromCompatibilityFlag() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.kafka.streams.properties.upgrade.from", "2.3");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> KafkaStreamsConfig.validateCooperativeRebalancingConfig(environment)
        );

        assertTrue(ex.getMessage().contains("upgrade.from"));
    }

    @Test
    void allowsStartupWhenNoUpgradeFromCompatibilityFlagIsPresent() {
        MockEnvironment environment = new MockEnvironment();

        assertDoesNotThrow(() -> KafkaStreamsConfig.validateCooperativeRebalancingConfig(environment));
    }
}
