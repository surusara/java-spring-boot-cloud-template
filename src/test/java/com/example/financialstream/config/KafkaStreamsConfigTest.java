package com.example.financialstream.config;

import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void buildsStreamsConfigurationWithOptionalMembershipAndSecuritySettings() {
        KafkaStreamsConfiguration configuration = configuration(
                "payments-pod-1",
                "SASL_SSL",
                "PLAIN",
                "org.example.LoginModule required;"
        );

        var properties = configuration.asProperties();
        assertEquals("payments-stream-test", properties.get(StreamsConfig.APPLICATION_ID_CONFIG));
        assertEquals("exactly_once_v2", properties.get(StreamsConfig.PROCESSING_GUARANTEE_CONFIG));
        assertEquals("payments-pod-1", properties.get("consumer.group.instance.id"));
        assertEquals("read_committed", properties.get("consumer.isolation.level"));
        assertEquals("SASL_SSL", properties.get("security.protocol"));
        assertEquals("PLAIN", properties.get("sasl.mechanism"));
        assertEquals("org.example.LoginModule required;", properties.get("sasl.jaas.config"));
    }

    @Test
    void omitsBlankOptionalMembershipAndSecuritySettings() {
        var properties = configuration(" ", null, "", null).asProperties();

        assertFalse(properties.containsKey("consumer.group.instance.id"));
        assertFalse(properties.containsKey("security.protocol"));
        assertFalse(properties.containsKey("sasl.mechanism"));
        assertFalse(properties.containsKey("sasl.jaas.config"));
    }

    private KafkaStreamsConfiguration configuration(
            String groupInstanceId,
            String securityProtocol,
            String saslMechanism,
            String saslJaasConfig
    ) {
        return new KafkaStreamsConfig().paymentsStreamsConfiguration(
                "localhost:9092",
                "payments-stream-test",
                "exactly_once_v2",
                2,
                1_000,
                "/tmp/kafka-streams-test",
                1,
                0,
                1_000,
                groupInstanceId,
                false,
                "latest",
                "read_committed",
                100,
                720_000,
                720_000,
                10_000,
                30_000,
                500,
                52_428_800,
                16_384,
                500,
                1_048_576,
                "all",
                5,
                65_536,
                67_108_864,
                securityProtocol,
                saslMechanism,
                saslJaasConfig
        );
    }
}
