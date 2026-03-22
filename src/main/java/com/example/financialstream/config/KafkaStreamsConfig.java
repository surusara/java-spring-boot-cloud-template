package com.example.financialstream.config;

import com.example.financialstream.circuit.BreakerControlProperties;
import com.example.financialstream.kafka.CustomDeserializationExceptionHandler;
import com.example.financialstream.kafka.PaymentsRecordProcessorSupplier;
import com.example.financialstream.kafka.StreamFatalExceptionHandler;
import com.example.financialstream.model.InputEvent;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(BreakerControlProperties.class)
public class KafkaStreamsConfig {

    @Bean(name = "paymentsStreamsConfiguration")
    public KafkaStreamsConfiguration paymentsStreamsConfiguration(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${app.stream.application-id:payments-stream-v1}") String applicationId,
            @Value("${app.stream.processing-guarantee:exactly_once_v2}") String processingGuarantee,
            @Value("${app.stream.num-stream-threads:1}") int numStreamThreads,
            @Value("${app.stream.commit-interval-ms:1000}") int commitIntervalMs,
            @Value("${app.stream.state-dir:/tmp/kafka-streams}") String stateDir,
            @Value("${app.stream.replication-factor:3}") int replicationFactor,
            @Value("${app.stream.num-standby-replicas:0}") int numStandbyReplicas,
            @Value("${app.stream.max-task-idle-ms:0}") long maxTaskIdleMs,
            // Static membership: pod hostname as member ID. Prevents rebalance storms during KEDA scaling.
            @Value("${app.stream.group-instance-id:#{null}}") String groupInstanceId,
            // Don't trigger rebalance on graceful close. Combined with session-timeout-ms, KEDA can remove pods safely.
            @Value("${app.stream.internal-leave-group-on-close:false}") boolean internalLeaveGroupOnClose,
            @Value("${app.stream.consumer.auto-offset-reset:latest}") String autoOffsetReset,
            @Value("${app.stream.consumer.max-poll-records:250}") int maxPollRecords,
            // 12 min: must be >= session-timeout-ms. Covers max circuit breaker delay (10m) + 2m buffer.
            @Value("${app.stream.consumer.max-poll-interval-ms:720000}") int maxPollIntervalMs,
            // 12 min: covers max circuit breaker restart delay (10m) + 2m buffer.
            // Partitions stay reserved on this pod during breaker OPEN. Dead pods caught by K8s liveness probe.
            @Value("${app.stream.consumer.session-timeout-ms:720000}") int sessionTimeoutMs,
            // 10 sec: must be < session-timeout-ms / 3 (720000/3 = 240s). Well within limit.
            @Value("${app.stream.consumer.heartbeat-interval-ms:10000}") int heartbeatIntervalMs,
            @Value("${app.stream.consumer.request-timeout-ms:30000}") int requestTimeoutMs,
            @Value("${app.stream.consumer.retry-backoff-ms:500}") int retryBackoffMs,
            @Value("${app.stream.consumer.fetch-max-bytes:52428800}") int fetchMaxBytes,
            @Value("${app.stream.consumer.fetch-min-bytes:16384}") int fetchMinBytes,
            @Value("${app.stream.consumer.fetch-max-wait-ms:500}") int fetchMaxWaitMs,
            @Value("${app.stream.consumer.max-partition-fetch-bytes:1048576}") int maxPartitionFetchBytes,
            @Value("${app.stream.producer.acks:all}") String producerAcks,
            @Value("${app.stream.producer.linger-ms:5}") int producerLingerMs,
            @Value("${app.stream.producer.batch-size:65536}") int producerBatchSize,
            @Value("${app.stream.producer.buffer-memory:67108864}") long producerBufferMemory,
            // --- Confluent Cloud SASL/SSL ---
            @Value("${app.stream.security.protocol:#{null}}") String securityProtocol,
            @Value("${app.stream.sasl.mechanism:#{null}}") String saslMechanism,
            @Value("${app.stream.sasl.jaas.config:#{null}}") String saslJaasConfig) {

        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, processingGuarantee);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, numStreamThreads);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, commitIntervalMs);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, replicationFactor);
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, numStandbyReplicas);
        props.put(StreamsConfig.MAX_TASK_IDLE_MS_CONFIG, maxTaskIdleMs);
        // Disable state store cache — stateless topology, nothing to cache.
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0L);
        // Let Kafka Streams optimize topology (merge repartition topics, etc.).
        props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                CustomDeserializationExceptionHandler.class);

        // Static membership: prevents rebalance storms during KEDA scale-up/down.
        // Each pod gets a unique group.instance.id (its hostname), so Kafka recognizes
        // returning pods and skips rebalance if they rejoin within session.timeout.ms.
        if (groupInstanceId != null && !groupInstanceId.isBlank()) {
            props.put("group.instance.id", groupInstanceId);
        }
        // Don't trigger rebalance when consumer shuts down gracefully.
        // Kafka Streams will NOT send a LeaveGroup request, so the coordinator
        // waits session.timeout.ms before reassigning partitions.
        props.put("internal.leave.group.on.close", internalLeaveGroupOnClose);

        props.put("main.consumer.auto.offset.reset", autoOffsetReset);
        props.put("consumer.max.poll.records", maxPollRecords);
        props.put("consumer.max.poll.interval.ms", maxPollIntervalMs);
        props.put("consumer.session.timeout.ms", sessionTimeoutMs);
        props.put("consumer.heartbeat.interval.ms", heartbeatIntervalMs);
        props.put("consumer.request.timeout.ms", requestTimeoutMs);
        props.put("consumer.retry.backoff.ms", retryBackoffMs);
        props.put("consumer.fetch.max.bytes", fetchMaxBytes);
        props.put("consumer.fetch.min.bytes", fetchMinBytes);
        props.put("consumer.fetch.max.wait.ms", fetchMaxWaitMs);
        props.put("consumer.max.partition.fetch.bytes", maxPartitionFetchBytes);

        // Confluent Cloud note (Kafka 3.9.x):
        // client.dns.lookup=use_all_dns_ips is the default since Kafka 2.6.
        // Resolves all IPs for a bootstrap hostname and tries each for failover.
        // No need to set explicitly. Uncomment if running on Kafka < 2.6:
        //   props.put("consumer.client.dns.lookup", "use_all_dns_ips");
        //   props.put("producer.client.dns.lookup", "use_all_dns_ips");

        props.put("producer.acks", producerAcks);
        props.put("producer.linger.ms", producerLingerMs);
        props.put("producer.batch.size", producerBatchSize);
        props.put("producer.buffer.memory", producerBufferMemory);

        // --- Confluent Cloud SASL/SSL ---
        // When connecting to Confluent Cloud, security.protocol and sasl.* are required.
        // These propagate to consumers, producers, and admin clients created from this config.
        if (securityProtocol != null && !securityProtocol.isBlank()) {
            props.put("security.protocol", securityProtocol);
        }
        if (saslMechanism != null && !saslMechanism.isBlank()) {
            props.put("sasl.mechanism", saslMechanism);
        }
        if (saslJaasConfig != null && !saslJaasConfig.isBlank()) {
            props.put("sasl.jaas.config", saslJaasConfig);
        }

        return new KafkaStreamsConfiguration(props);
    }

    @Bean(name = "paymentsStreamsBuilder")
    public StreamsBuilderFactoryBean paymentsStreamsBuilder(KafkaStreamsConfiguration kafkaStreamsConfiguration,
                                                            StreamFatalExceptionHandler uncaughtExceptionHandler) {
        StreamsBuilderFactoryBean factoryBean = new StreamsBuilderFactoryBean(kafkaStreamsConfiguration);
        factoryBean.setStreamsUncaughtExceptionHandler(uncaughtExceptionHandler);
        return factoryBean;
    }

    @Bean
    public KStream<String, InputEvent> paymentsStream(StreamsBuilder streamsBuilder,
                                                      PaymentsRecordProcessorSupplier processorSupplier,
                                                      @Value("${app.input.topic:payments.input}") String inputTopic) {
        JsonSerde<InputEvent> inputSerde = new JsonSerde<>(InputEvent.class);
        KStream<String, InputEvent> stream = streamsBuilder.stream(inputTopic, Consumed.with(Serdes.String(), inputSerde));
        stream.process(processorSupplier::get);
        return stream;
    }
}
