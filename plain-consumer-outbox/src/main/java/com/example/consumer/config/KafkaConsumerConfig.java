package com.example.consumer.config;

import com.example.consumer.avro.PaymentInput;
import com.example.consumer.circuit.BreakerOpenRedeliveryException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@EnableConfigurationProperties(AppProperties.class)
public class KafkaConsumerConfig {

    private final AppProperties props;

    public KafkaConsumerConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public ConsumerFactory<String, PaymentInput> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.security.protocol:SASL_SSL}") String securityProtocol,
            @Value("${app.kafka.security.sasl-mechanism:PLAIN}") String saslMechanism,
            @Value("${app.kafka.security.sasl-jaas-config:}") String saslJaasConfig,
            @Value("${app.kafka.schema-registry.url}") String schemaRegistryUrl,
            @Value("${app.kafka.schema-registry.basic-auth-user-info:}") String schemaRegistryAuth) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.getConsumer().getGroupId());

        // Manual offset management: the container commits AFTER the listener returns (AckMode.RECORD).
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getConsumer().getMaxPollRecords());
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, props.getConsumer().getMaxPollIntervalMs());
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, props.getConsumer().getSessionTimeoutMs());
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, props.getConsumer().getHeartbeatIntervalMs());
        // Cooperative rebalancing: incremental revocation, no stop-the-world reshuffle on scale.
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        // Read only committed data from upstream transactional producers.
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // Confluent Cloud auth (SASL_SSL / PLAIN with API key+secret in the JAAS config).
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
        if (!saslJaasConfig.isBlank()) {
            config.put(SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig);
        }

        // Key = String; Value = Avro via Schema Registry, wrapped so a poison pill doesn't kill the thread.
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        if (!schemaRegistryAuth.isBlank()) {
            config.put("basic.auth.credentials.source", "USER_INFO");
            config.put("basic.auth.user.info", schemaRegistryAuth);
        }

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentInput> kafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentInput> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, PaymentInput> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // Threads per pod. Keep concurrency <= partitions/pod for full utilisation.
        factory.setConcurrency(props.getConsumer().getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        factory.setCommonErrorHandler(breakerAwareErrorHandler());
        return factory;
    }

    /**
     * Two behaviours:
     * <ul>
     *   <li><b>Breaker OPEN</b> ({@code CallNotPermittedException}): go straight to the recoverer,
     *       which re-throws so the offset is NOT committed → the record is re-delivered after the
     *       container resumes. No record is skipped during an outage.</li>
     *   <li><b>Any other error</b>: retry a few times with backoff, then log-and-skip so a single
     *       poison-pill record can't wedge the partition forever (business dedupe makes replays safe).</li>
     * </ul>
     */
    private DefaultErrorHandler breakerAwareErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    if (isBreakerOpen(exception)) {
                        throw new BreakerOpenRedeliveryException(exception);
                    }
                    // Retries exhausted for a genuine bad record — skip it (offset advances).
                    // (Logged by the handler; hook a DLT publisher here if required.)
                },
                new FixedBackOff(1_000L, 3L));
        // Don't waste retries on an open breaker — recover (withhold) immediately.
        errorHandler.addNotRetryableExceptions(CallNotPermittedException.class);
        return errorHandler;
    }

    private boolean isBreakerOpen(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CallNotPermittedException) {
                return true;
            }
        }
        return false;
    }
}
