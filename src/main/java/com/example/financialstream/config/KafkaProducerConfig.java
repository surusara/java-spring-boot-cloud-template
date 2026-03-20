package com.example.financialstream.config;

import com.example.financialstream.model.OutputEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Production-grade transactional Kafka producer configuration.
 *
 * This producer guarantees exactly-once delivery to the output topic by combining:
 *   1. Idempotence   — broker deduplicates retried sends via producer ID + sequence number
 *   2. Transactions  — atomically commits batches; downstream consumers with
 *                      isolation.level=read_committed only see committed records
 *   3. Zombie fencing — old/crashed producers with the same transactional.id are
 *                       fenced (ProducerFencedException), preventing split-brain duplicates
 *
 * Downstream consumers MUST set isolation.level=read_committed to benefit from transactions.
 */
@Configuration
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    @Bean
    public ProducerFactory<String, OutputEvent> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,

            // ===== DURABILITY & RELIABILITY =====
            // "all" = message acknowledged by ALL in-sync replicas before producer considers it committed.
            // Guarantees zero data loss even if a broker dies immediately after ack.
            // Alternative: "1" = leader-only ack (faster, but risks data loss on leader failure).
            @Value("${spring.kafka.producer.acks:all}") String acks,

            // Enables the idempotent producer. The broker assigns a unique Producer ID (PID) and
            // sequence number to each message. If the producer retries a send (e.g., network timeout),
            // the broker detects the duplicate sequence number and silently discards it.
            // Prevents retry-duplicates at the broker level.
            // Requires: acks=all, retries>0, max.in.flight.requests<=5 (enforced by Kafka when true).
            @Value("${spring.kafka.producer.enable-idempotence:true}") boolean enableIdempotence,

            // Max unacknowledged requests per broker connection. With idempotence enabled, Kafka
            // guarantees ordering even with up to 5 in-flight requests — it rejects out-of-order
            // sequence numbers and re-enqueues them. Value of 5 is the maximum allowed with
            // idempotence and provides good throughput without message re-ordering risk.
            @Value("${spring.kafka.producer.max-in-flight-requests-per-connection:5}") int maxInFlight,

            // Number of retry attempts for failed sends. Integer.MAX_VALUE = effectively infinite.
            // The producer retries until delivery-timeout-ms expires. Combined with idempotence,
            // retries are safe — broker deduplicates via sequence numbers.
            // We rely on delivery-timeout-ms to bound the total retry window.
            @Value("${spring.kafka.producer.retries:2147483647}") int retries,

            // ===== TIMEOUT CONFIGURATION =====
            // Total time allowed for a message to be delivered, including all retries.
            // If a message cannot be acknowledged within this window, send() fails with TimeoutException.
            // 120s (2 min) gives headroom for transient broker issues while failing fast enough
            // for financial pipeline SLAs. Must be >= request-timeout-ms + linger-ms.
            @Value("${spring.kafka.producer.delivery-timeout-ms:120000}") int deliveryTimeoutMs,

            // Time to wait for a broker response to a single produce request.
            // 30s is generous — most acks return in <100ms. If a broker is unresponsive beyond 30s,
            // the request fails and is retried (up to delivery-timeout-ms total).
            @Value("${spring.kafka.producer.request-timeout-ms:30000}") int requestTimeoutMs,

            // Max time send() blocks when the buffer is full or metadata is unavailable.
            // 60s allows the producer to wait for buffer space to free up rather than immediately throwing.
            // If still blocked after 60s, throws TimeoutException.
            @Value("${spring.kafka.producer.max-block-ms:60000}") long maxBlockMs,

            // ===== BATCHING & THROUGHPUT =====
            // How long to wait before sending a batch, even if it's not full.
            // 5ms adds minimal latency but allows the producer to batch multiple records into a single
            // request — significantly improving throughput. Set to 0 for lowest latency (wastes network).
            @Value("${spring.kafka.producer.linger-ms:5}") int lingerMs,

            // Max size of a single batch in bytes. 64KB is a good balance for financial events (~1-2KB each).
            // Larger batches = fewer requests = higher throughput, but more memory and higher latency.
            // The producer accumulates records per-partition until batch-size or linger-ms is reached.
            @Value("${spring.kafka.producer.batch-size:65536}") int batchSize,

            // Total memory the producer can use to buffer records waiting to be sent. 64MB is the default.
            // If the buffer fills up, send() blocks for up to max-block-ms.
            // With 3M trades/day, 64MB provides ample headroom. Increase if you see BufferExhaustedException.
            @Value("${spring.kafka.producer.buffer-memory:67108864}") long bufferMemory,

            // Compression algorithm applied to each batch before sending. Reduces network bandwidth
            // and broker disk usage. Financial events (~1-2KB JSON) compress well — expect 40-60% reduction.
            // Options: none, gzip (best ratio, slow), snappy (fast, good ratio), lz4 (fastest), zstd (best overall).
            // Snappy is recommended for financial pipelines — fast compression with low CPU overhead.
            @Value("${spring.kafka.producer.compression-type:snappy}") String compressionType,

            // ===== TRANSACTIONAL PRODUCER (Exactly-Once Delivery) =====
            // Transaction timeout: how long the broker waits for a transaction to be committed or aborted.
            // If a transaction is not completed within this time, the broker force-aborts it.
            // 60s (default) prevents zombie transactions from blocking consumers with read_committed isolation.
            // Must be <= broker's transaction.max.timeout.ms (default 900s / 15 min).
            @Value("${spring.kafka.producer.transaction-timeout-ms:60000}") int transactionTimeoutMs,

            // Transactional ID prefix: enables Kafka transactions on this producer.
            // Spring Kafka appends a unique suffix (incrementing counter) to create per-instance IDs.
            // Uses ${random.uuid} for globally unique IDs across all pods/restarts.
            // Example: payments-producer-550e8400-e29b-41d4-a716-446655440000-0
            //
            // WHY ${random.uuid} INSTEAD OF ${HOSTNAME}:
            //   With Deployments (not StatefulSets), pod names change on every restart.
            //   This means ${HOSTNAME} gives a NEW transactional.id on restart — zombie fencing
            //   is broken anyway (fencing requires the SAME ID to be reused).
            //   UUID is honest: guaranteed uniqueness, no false sense of fencing.
            //   If you switch to StatefulSet, consider ${HOSTNAME} for real zombie fencing.
            //
            // WHY TRANSACTIONS:
            //   Without: a crash between send() and Streams commit → duplicate output on replay.
            //   With: output is committed atomically — downstream consumers (read_committed) skip uncommitted.
            //
            // ORPHAN CLEANUP:
            //   Orphaned transactional IDs (from crashed pods) are cleaned by the broker after
            //   transactional.id.expiration.ms (default 7 days). No manual intervention needed.
            @Value("${spring.kafka.producer.transactional-id-prefix:payments-producer-${random.uuid}-}") String transactionalIdPrefix) {

        Map<String, Object> config = new HashMap<>();

        // --- Connection ---
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // --- Serialization ---
        // String keys ensure consistent partition routing by event ID (same key → same partition).
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // JSON serializer for OutputEvent records.
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // --- Durability ---
        config.put(ProducerConfig.ACKS_CONFIG, acks);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, enableIdempotence);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlight);
        config.put(ProducerConfig.RETRIES_CONFIG, retries);

        // --- Timeouts ---
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);

        // --- Batching & Throughput ---
        config.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);

        // --- Transactions ---
        config.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, transactionTimeoutMs);

        DefaultKafkaProducerFactory<String, OutputEvent> factory = new DefaultKafkaProducerFactory<>(config);

        // Enable transactional producer by setting the ID prefix.
        // Spring Kafka appends a unique suffix per producer instance (e.g., payments-producer-pod-abc-0).
        // This activates: initTransactions() on first use, begin/commit/abort transaction lifecycle,
        // and zombie fencing (ProducerFencedException for old producers with same transactional.id).
        factory.setTransactionIdPrefix(transactionalIdPrefix);

        log.info("Transactional producer factory created with prefix '{}', acks={}, compression={}",
                transactionalIdPrefix, acks, compressionType);

        return factory;
    }

    @Bean
    public KafkaTemplate<String, OutputEvent> kafkaTemplate(ProducerFactory<String, OutputEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
