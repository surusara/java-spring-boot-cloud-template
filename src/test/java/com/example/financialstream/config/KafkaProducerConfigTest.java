package com.example.financialstream.config;

import com.example.financialstream.model.OutputEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaProducerConfigTest {

    @Test
    void createsTransactionalProducerFactoryWithReliabilitySettings() {
        KafkaProducerConfig config = new KafkaProducerConfig();

        ProducerFactory<String, OutputEvent> producerFactory = config.producerFactory(
                "localhost:9092",
                "all",
                true,
                5,
                Integer.MAX_VALUE,
                120_000,
                30_000,
                60_000,
                5,
                65_536,
                67_108_864,
                "snappy",
                60_000,
                "payments-producer-test-"
        );

        assertTrue(producerFactory instanceof DefaultKafkaProducerFactory);
        DefaultKafkaProducerFactory<String, OutputEvent> factory =
                (DefaultKafkaProducerFactory<String, OutputEvent>) producerFactory;
        assertEquals("payments-producer-test-", factory.getTransactionIdPrefix());
        assertEquals("localhost:9092", factory.getConfigurationProperties()
                .get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(true, factory.getConfigurationProperties()
                .get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals("all", factory.getConfigurationProperties().get(ProducerConfig.ACKS_CONFIG));
        assertEquals("snappy", factory.getConfigurationProperties()
                .get(ProducerConfig.COMPRESSION_TYPE_CONFIG));
    }

    @Test
    void createsKafkaTemplateFromProducerFactory() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        @SuppressWarnings("unchecked")
        ProducerFactory<String, OutputEvent> producerFactory = org.mockito.Mockito.mock(ProducerFactory.class);

        KafkaTemplate<String, OutputEvent> template = config.kafkaTemplate(producerFactory);

        assertSame(producerFactory, template.getProducerFactory());
    }
}
