package com.example.financialstream.runtime;

import com.example.financialstream.circuit.BreakerControlProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.ThreadMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Collects runtime configuration and state from all application components.
 * All data is local/in-memory — no external calls, no failure risk, instant response.
 *
 * Use case: call during deployments (dev → test → prod) to verify
 * environment-specific config is correct (bootstrap servers, DB URL, profiles, topics, etc.).
 */
@Service
public class RuntimeDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDiscoveryService.class);

    /**
     * Whitelist of Kafka Streams config keys safe to expose.
     * Any key NOT in this list is silently dropped.
     * Keys matching sensitive patterns are redacted by Sanitiser even if whitelisted.
     */
    private static final List<String> STREAMS_CONFIG_WHITELIST = List.of(
            "application.id",
            "bootstrap.servers",
            "processing.guarantee",
            "num.stream.threads",
            "commit.interval.ms",
            "state.dir",
            "replication.factor",
            "num.standby.replicas",
            "max.task.idle.ms",
            "default.deserialization.exception.handler",
            "group.instance.id",
            "internal.leave.group.on.close",
            "consumer.group.instance.id",
            "consumer.internal.leave.group.on.close",
            "main.consumer.auto.offset.reset",
            "consumer.isolation.level",
            "consumer.max.poll.records",
            "consumer.max.poll.interval.ms",
            "consumer.session.timeout.ms",
            "consumer.heartbeat.interval.ms",
            "consumer.request.timeout.ms",
            "consumer.retry.backoff.ms",
            "consumer.fetch.max.bytes",
            "consumer.max.partition.fetch.bytes",
            "producer.acks",
            "producer.linger.ms",
            "producer.batch.size",
            "producer.buffer.memory"
    );

    private final Environment environment;
    private final StreamsBuilderFactoryBean streamsBuilder;
    private final BreakerControlProperties breakerProps;
    private final ApplicationContext applicationContext;

    public RuntimeDiscoveryService(
            Environment environment,
            @Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilder,
            BreakerControlProperties breakerProps,
            ApplicationContext applicationContext) {
        this.environment = environment;
        this.streamsBuilder = streamsBuilder;
        this.breakerProps = breakerProps;
        this.applicationContext = applicationContext;
    }

    /**
     * Collect complete runtime discovery report.
     * Every value goes through Sanitiser — no credentials leak.
     */
    public Map<String, Object> discover() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("meta", collectMeta());
        report.put("pod", collectPod());
        report.put("jvm", collectJvm());
        report.put("server", collectServer());
        report.put("swagger", collectSwagger());
        report.put("kafkaStreams", collectKafkaStreams());
        report.put("streamsInstanceAudit", collectStreamsInstanceAudit());
        report.put("kafkaLag", collectKafkaLag());
        report.put("kafkaProducer", collectKafkaProducer());
        report.put("topics", collectTopics());
        report.put("database", collectDatabase());
        report.put("redis", collectRedis());
        report.put("circuitBreaker", collectCircuitBreaker());
        return report;
    }

    // ─── Meta ───────────────────────────────────────────────────────────

    private Map<String, Object> collectMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("application", prop("spring.application.name", "unknown"));

        String[] activeProfiles = environment.getActiveProfiles();
        meta.put("profiles", activeProfiles.length > 0 ? activeProfiles : environment.getDefaultProfiles());
        meta.put("generatedAt", Instant.now().toString());

        try {
            meta.put("springBootVersion", org.springframework.boot.SpringBootVersion.getVersion());
        } catch (Exception e) {
            meta.put("springBootVersion", "unknown");
        }

        return meta;
    }

    // ─── Pod (K8s Downward API) ─────────────────────────────────────────

    private Map<String, Object> collectPod() {
        Map<String, Object> pod = new LinkedHashMap<>();
        pod.put("podName", env("POD_NAME"));
        pod.put("namespace", env("POD_NAMESPACE"));
        pod.put("nodeName", env("NODE_NAME"));
        pod.put("podIp", env("POD_IP"));
        pod.put("hostname", env("HOSTNAME"));
        return pod;
    }

    // ─── JVM ────────────────────────────────────────────────────────────

    private Map<String, Object> collectJvm() {
        var rt = ManagementFactory.getRuntimeMXBean();
        var mem = ManagementFactory.getMemoryMXBean();
        var heap = mem.getHeapMemoryUsage();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("javaVersion", System.getProperty("java.version"));
        jvm.put("vmName", rt.getVmName());
        jvm.put("vmVendor", rt.getVmVendor());
        jvm.put("uptimeSeconds", rt.getUptime() / 1000);
        jvm.put("heapUsedMB", heap.getUsed() / (1024 * 1024));
        jvm.put("heapMaxMB", heap.getMax() / (1024 * 1024));
        jvm.put("nonHeapUsedMB", mem.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        return jvm;
    }

    // ─── Server ─────────────────────────────────────────────────────────

    private Map<String, Object> collectServer() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("port", prop("server.port", "8080"));
        server.put("contextPath", prop("server.servlet.context-path", "/"));
        server.put("managementPort", prop("management.server.port", prop("server.port", "8080")));
        return server;
    }

    // ─── Swagger ────────────────────────────────────────────────────────

    private Map<String, Object> collectSwagger() {
        Map<String, Object> swagger = new LinkedHashMap<>();
        swagger.put("apiDocsEnabled", prop("springdoc.api-docs.enabled", "true"));
        swagger.put("swaggerUiEnabled", prop("springdoc.swagger-ui.enabled", "true"));
        swagger.put("apiDocsPath", prop("springdoc.api-docs.path", "/v3/api-docs"));
        swagger.put("swaggerUiPath", prop("springdoc.swagger-ui.path", "/swagger-ui.html"));
        return swagger;
    }

    // ─── Kafka Streams ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectKafkaStreams() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Properties streamsCfg = streamsBuilder.getStreamsConfiguration();
            Map<Object, Object> cfgMap = streamsCfg == null ? Map.of() : streamsCfg;

            KafkaStreams ks = streamsBuilder.getKafkaStreams();
            result.put("state", ks == null ? "NOT_STARTED" : ks.state().name());
            result.put("applicationId", cfgMap.getOrDefault("application.id", "unknown"));

            // Thread metadata — shows active/standby tasks per thread
            List<Map<String, Object>> threads = new ArrayList<>();
            if (ks != null) {
                try {
                    Set<ThreadMetadata> threadMetas = ks.metadataForLocalThreads();
                    for (ThreadMetadata tm : threadMetas) {
                        Map<String, Object> threadInfo = new LinkedHashMap<>();
                        threadInfo.put("threadName", tm.threadName());
                        threadInfo.put("threadState", String.valueOf(tm.threadState()));
                        threadInfo.put("activeTasks", tm.activeTasks().size());
                        threadInfo.put("standbyTasks", tm.standbyTasks().size());
                        threads.add(threadInfo);
                    }
                } catch (Exception e) {
                    log.debug("Could not retrieve thread metadata: {}", e.getMessage());
                }
            }
            result.put("threads", threads);

            // Whitelisted + sanitised config
            result.put("config", Sanitiser.sanitiseMap(cfgMap, STREAMS_CONFIG_WHITELIST));

        } catch (Exception e) {
            result.put("state", "ERROR");
            result.put("error", e.getMessage());
        }

        return result;
    }

    // ─── Streams Instance Audit ──────────────────────────────────────────

    /**
     * Detects ALL StreamsBuilderFactoryBean instances in the ApplicationContext.
     * If Spring Boot's KafkaAutoConfiguration is not excluded, it silently creates
     * a second instance ("defaultKafkaStreamsBuilder") with application.id = spring.application.name.
     * Two instances = two consumer groups = every message consumed twice.
     */
    private Map<String, Object> collectStreamsInstanceAudit() {
        Map<String, Object> audit = new LinkedHashMap<>();

        try {
            Map<String, StreamsBuilderFactoryBean> beans =
                    applicationContext.getBeansOfType(StreamsBuilderFactoryBean.class);

            audit.put("instanceCount", beans.size());

            // Collect instance details and resolve each application.id
            List<Map<String, Object>> instances = new ArrayList<>();
            Map<String, List<String>> appIdToBeans = new LinkedHashMap<>();

            for (Map.Entry<String, StreamsBuilderFactoryBean> entry : beans.entrySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                String beanName = entry.getKey();
                info.put("beanName", beanName);

                StreamsBuilderFactoryBean factory = entry.getValue();
                String applicationId = "unknown";
                try {
                    Properties cfg = factory.getStreamsConfiguration();
                    if (cfg != null) {
                        applicationId = cfg.getProperty("application.id", "unknown");
                        info.put("numStreamThreads", cfg.getProperty("num.stream.threads", "1"));
                    } else {
                        info.put("numStreamThreads", "1");
                    }
                } catch (Exception e) {
                    applicationId = "ERROR: " + e.getMessage();
                }
                info.put("applicationId", applicationId);

                // Track which beans share the same application.id
                appIdToBeans.computeIfAbsent(applicationId, k -> new ArrayList<>()).add(beanName);

                try {
                    KafkaStreams ks = factory.getKafkaStreams();
                    info.put("state", ks != null ? ks.state().name() : "NOT_STARTED");
                } catch (Exception e) {
                    info.put("state", "ERROR: " + e.getMessage());
                }

                instances.add(info);
            }
            audit.put("instances", instances);

            // Duplicate risk = two or more beans sharing the SAME application.id (= same consumer group)
            // Multiple beans with DIFFERENT application.ids is a valid multi-topology setup
            Map<String, List<String>> duplicates = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : appIdToBeans.entrySet()) {
                if (e.getValue().size() > 1) {
                    duplicates.put(e.getKey(), e.getValue());
                }
            }

            audit.put("duplicateRisk", !duplicates.isEmpty());
            audit.put("distinctApplicationIds", appIdToBeans.size());

            if (!duplicates.isEmpty()) {
                audit.put("duplicatedApplicationIds", duplicates);
                audit.put("WARNING", "Beans sharing the same application.id join the SAME consumer group — "
                        + "every message is consumed once per instance, causing double-processing. "
                        + "Affected application.ids: " + duplicates.keySet() + ". "
                        + "Fix: assign unique application.id per bean, or if the extra bean is from "
                        + "KafkaAutoConfiguration, add @SpringBootApplication(exclude = KafkaAutoConfiguration.class)");
            }

        } catch (Exception e) {
            audit.put("error", "Could not audit streams instances: " + e.getMessage());
        }

        return audit;
    }

    // ─── Kafka Producer ─────────────────────────────────────────────────

    private Map<String, Object> collectKafkaProducer() {
        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("bootstrapServers", Sanitiser.sanitise("bootstrap.servers",
                prop("spring.kafka.bootstrap-servers", "not set")));
        producer.put("acks", prop("spring.kafka.producer.acks", "not set"));
        producer.put("enableIdempotence", prop("spring.kafka.producer.enable-idempotence", "not set"));
        producer.put("compressionType", prop("spring.kafka.producer.compression-type", "not set"));
        producer.put("batchSize", prop("spring.kafka.producer.batch-size", "not set"));
        producer.put("lingerMs", prop("spring.kafka.producer.linger-ms", "not set"));
        producer.put("bufferMemory", prop("spring.kafka.producer.buffer-memory", "not set"));
        producer.put("maxInFlightRequests", prop("spring.kafka.producer.max-in-flight-requests-per-connection", "not set"));
        producer.put("retries", prop("spring.kafka.producer.retries", "not set"));
        producer.put("deliveryTimeoutMs", prop("spring.kafka.producer.delivery-timeout-ms", "not set"));
        producer.put("requestTimeoutMs", prop("spring.kafka.producer.request-timeout-ms", "not set"));
        producer.put("maxBlockMs", prop("spring.kafka.producer.max-block-ms", "not set"));
        producer.put("transactionalIdPrefix", prop("spring.kafka.producer.transactional-id-prefix", "not set"));
        producer.put("transactionTimeoutMs", prop("spring.kafka.producer.transaction-timeout-ms", "not set"));
        return producer;
    }

    // ─── Topics ─────────────────────────────────────────────────────────

    private Map<String, Object> collectTopics() {
        Map<String, Object> topics = new LinkedHashMap<>();
        topics.put("input", prop("app.input.topic", "not set"));
        topics.put("output", prop("app.output.topic", "not set"));
        return topics;
    }

    // ─── Kafka Lag ──────────────────────────────────────────────────────

    /**
     * Collects consumer lag for each Kafka Streams instance's consumer group.
     * Uses AdminClient to query committed offsets and end offsets per partition.
     * Requires broker connectivity — wrapped in try/catch with a 5-second timeout.
     */
    private Map<String, Object> collectKafkaLag() {
        Map<String, Object> lagReport = new LinkedHashMap<>();

        try {
            Map<String, StreamsBuilderFactoryBean> beans =
                    applicationContext.getBeansOfType(StreamsBuilderFactoryBean.class);

            // Resolve bootstrap servers from the primary streams config
            String bootstrapServers = resolveBootstrapServers();
            if (bootstrapServers == null) {
                lagReport.put("error", "No bootstrap.servers found in Kafka Streams or Spring Kafka config");
                return lagReport;
            }

            Properties adminProps = new Properties();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
            adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);

            // Propagate SASL/SSL properties from Streams config for Confluent Cloud
            copySecurityProperties(adminProps);

            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                List<Map<String, Object>> consumerGroups = new ArrayList<>();

                for (Map.Entry<String, StreamsBuilderFactoryBean> entry : beans.entrySet()) {
                    StreamsBuilderFactoryBean factory = entry.getValue();
                    String groupId = resolveGroupId(factory);
                    if (groupId == null) continue;

                    Map<String, Object> groupLag = collectGroupLag(adminClient, groupId);
                    groupLag.put("beanName", entry.getKey());
                    consumerGroups.add(groupLag);
                }

                lagReport.put("consumerGroups", consumerGroups);
            }
        } catch (Exception e) {
            log.debug("Could not collect Kafka lag: {}", e.getMessage());
            lagReport.put("error", "Failed to query broker: " + e.getMessage());
        }

        return lagReport;
    }

    private Map<String, Object> collectGroupLag(AdminClient adminClient, String groupId) {
        Map<String, Object> groupLag = new LinkedHashMap<>();
        groupLag.put("consumerGroup", groupId);

        try {
            // Get committed offsets for the consumer group
            ListConsumerGroupOffsetsResult offsetsResult = adminClient.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                    offsetsResult.partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);

            if (committedOffsets == null || committedOffsets.isEmpty()) {
                groupLag.put("status", "NO_COMMITTED_OFFSETS");
                return groupLag;
            }

            // Get end offsets for those same partitions
            Map<TopicPartition, OffsetSpec> endOffsetRequest = new LinkedHashMap<>();
            for (TopicPartition tp : committedOffsets.keySet()) {
                endOffsetRequest.put(tp, OffsetSpec.latest());
            }
            ListOffsetsResult endOffsetsResult = adminClient.listOffsets(endOffsetRequest);

            // Compute per-partition lag, grouped by topic
            Map<String, List<Map<String, Object>>> topicPartitions = new LinkedHashMap<>();
            Map<String, Long> topicTotalLag = new LinkedHashMap<>();
            Map<String, Integer> topicPartitionCount = new LinkedHashMap<>();
            long groupTotalLag = 0;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committed = entry.getValue().offset();

                long endOffset;
                try {
                    endOffset = endOffsetsResult.partitionResult(tp).get(5, TimeUnit.SECONDS).offset();
                } catch (Exception e) {
                    log.debug("Could not get end offset for {}: {}", tp, e.getMessage());
                    continue;
                }

                long lag = Math.max(0, endOffset - committed);
                groupTotalLag += lag;

                String topic = tp.topic();
                topicTotalLag.merge(topic, lag, Long::sum);
                topicPartitionCount.merge(topic, 1, Integer::sum);

                topicPartitions.computeIfAbsent(topic, k -> new ArrayList<>()).add(Map.of(
                        "partition", tp.partition(),
                        "committedOffset", committed,
                        "endOffset", endOffset,
                        "lag", lag
                ));
            }

            // Build per-topic summary
            List<Map<String, Object>> topics = new ArrayList<>();
            for (String topic : topicPartitions.keySet()) {
                Map<String, Object> topicInfo = new LinkedHashMap<>();
                topicInfo.put("topic", topic);
                topicInfo.put("partitionCount", topicPartitionCount.getOrDefault(topic, 0));
                topicInfo.put("totalLag", topicTotalLag.getOrDefault(topic, 0L));
                topicInfo.put("partitions", topicPartitions.get(topic));
                topics.add(topicInfo);
            }

            groupLag.put("totalLag", groupTotalLag);
            groupLag.put("topics", topics);

        } catch (Exception e) {
            log.debug("Could not collect lag for group {}: {}", groupId, e.getMessage());
            groupLag.put("error", "Failed to query lag: " + e.getMessage());
        }

        return groupLag;
    }

    /**
     * Security-related Kafka property prefixes that must be propagated to the AdminClient
     * for Confluent Cloud (SASL_SSL) authentication.
     */
    private static final List<String> SECURITY_PROPERTY_PREFIXES = List.of(
            "security.protocol",
            "sasl.mechanism",
            "sasl.jaas.config",
            "sasl.login.",
            "sasl.client.callback.handler.class",
            "ssl.endpoint.identification.algorithm",
            "ssl.truststore.",
            "ssl.keystore.",
            "ssl.key.password"
    );

    /**
     * Copies security-related properties (SASL/SSL) from the Streams configuration
     * into the target Properties. Required for AdminClient to authenticate against
     * Confluent Cloud (SASL_SSL).
     */
    private void copySecurityProperties(Properties target) {
        try {
            Properties streamsCfg = streamsBuilder.getStreamsConfiguration();
            if (streamsCfg == null) return;

            for (String key : streamsCfg.stringPropertyNames()) {
                for (String prefix : SECURITY_PROPERTY_PREFIXES) {
                    if (key.equals(prefix) || key.startsWith(prefix)) {
                        target.put(key, streamsCfg.getProperty(key));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not copy security properties from streams config: {}", e.getMessage());
        }
    }

    private String resolveBootstrapServers() {
        // Try streams config first, fall back to spring.kafka.bootstrap-servers
        try {
            Properties cfg = streamsBuilder.getStreamsConfiguration();
            if (cfg != null) {
                String servers = cfg.getProperty("bootstrap.servers");
                if (servers != null) return servers;
            }
        } catch (Exception e) {
            log.debug("Could not read streams config for bootstrap.servers: {}", e.getMessage());
        }
        return prop("spring.kafka.bootstrap-servers", null);
    }

    private String resolveGroupId(StreamsBuilderFactoryBean factory) {
        try {
            Properties cfg = factory.getStreamsConfiguration();
            return cfg != null ? cfg.getProperty("application.id") : null;
        } catch (Exception e) {
            log.debug("Could not resolve application.id: {}", e.getMessage());
            return null;
        }
    }

    // ─── Database (PostgreSQL) ──────────────────────────────────────────

    private Map<String, Object> collectDatabase() {
        String dbUrl = prop("spring.datasource.url", null);
        if (dbUrl == null) {
            return Map.of("status", "NOT_CONFIGURED",
                    "note", "No spring.datasource.url found. Add PostgreSQL dependency and config when ready.");
        }

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("status", "CONFIGURED");
        db.put("url", Sanitiser.sanitiseJdbcUrl(dbUrl));
        db.put("driverClassName", prop("spring.datasource.driver-class-name", "auto-detected"));

        // Hikari pool settings (show config, not runtime state — no external calls)
        db.put("hikari.maximumPoolSize", prop("spring.datasource.hikari.maximum-pool-size", "10"));
        db.put("hikari.minimumIdle", prop("spring.datasource.hikari.minimum-idle", "10"));
        db.put("hikari.connectionTimeoutMs", prop("spring.datasource.hikari.connection-timeout", "30000"));

        return db;
    }

    // ─── Redis ──────────────────────────────────────────────────────────

    private Map<String, Object> collectRedis() {
        String redisHost = prop("spring.data.redis.host", null);
        if (redisHost == null) {
            return Map.of("status", "NOT_CONFIGURED",
                    "note", "No spring.data.redis.host found. Add Redis dependency and config when ready.");
        }

        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("status", "CONFIGURED");
        redis.put("host", redisHost);
        redis.put("port", prop("spring.data.redis.port", "6379"));
        redis.put("database", prop("spring.data.redis.database", "0"));
        redis.put("ssl", prop("spring.data.redis.ssl.enabled", "false"));
        redis.put("timeout", prop("spring.data.redis.timeout", "not set"));
        // password is NEVER exposed
        return redis;
    }

    // ─── Circuit Breaker ────────────────────────────────────────────────

    private Map<String, Object> collectCircuitBreaker() {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("failureRateThreshold", breakerProps.getFailureRateThreshold());
        cb.put("timeWindowSeconds", breakerProps.getTimeWindowSeconds());
        cb.put("minimumNumberOfCalls", breakerProps.getMinimumNumberOfCalls());
        cb.put("permittedCallsInHalfOpenState", breakerProps.getPermittedCallsInHalfOpenState());
        cb.put("maxWaitInHalfOpenState", breakerProps.getMaxWaitInHalfOpenState().toString());
        cb.put("restartDelays", breakerProps.getRestartDelays().stream()
                .map(d -> d.toString())
                .toList());
        return cb;
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private String prop(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private String env(String name) {
        return System.getenv(name); // null in local dev — that's fine, JSON shows null
    }
}
