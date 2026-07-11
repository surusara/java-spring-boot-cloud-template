package com.example.consumer.config;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.security.protocol:SASL_SSL}") String securityProtocol,
            @Value("${app.kafka.security.sasl-mechanism:PLAIN}") String saslMechanism,
            @Value("${app.kafka.security.sasl-jaas-config:}") String saslJaasConfig,
            @Value("${app.kafka.schema-registry.url}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.basic-auth-user-info:}") String schemaRegistryAuth) {

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Idempotent producer: dedupes producer-side retries (does NOT dedupe outbox re-publishes;
        // that is handled by downstream idempotency on eventId). acks=all for durability.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
        if (!saslJaasConfig.isBlank()) {
            config.put(SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig);
        }

        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        if (!schemaRegistryAuth.isBlank()) {
            config.put(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO");
            config.put(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG, schemaRegistryAuth);
        }

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
