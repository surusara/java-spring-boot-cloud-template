package com.example.financialstream.monitoring;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Provides per-partition Kafka consumer lag breakdown for configured consumer groups.
 *
 * <p>Uses Kafka AdminClient to fetch committed offsets and end offsets, then computes:
 * <ul>
 *   <li>Total lag per group</li>
 *   <li>Per-partition lag with committed/end offsets</li>
 *   <li>Average lag per partition</li>
 *   <li>Max lagging partition</li>
 *   <li>Count of partitions with lag &gt; 0</li>
 * </ul>
 *
 * <p>Configured consumer groups: {@code app.monitoring.kafka-consumer-groups}.
 * Lag thresholds for UI coloring: {@code app.monitoring.lag-threshold-warning} and
 * {@code app.monitoring.lag-threshold-critical}.
 */
@Service
public class KafkaLagMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMonitoringService.class);

    private final MonitoringProperties props;
    private final StreamsBuilderFactoryBean streamsBuilder;
    private final Environment environment;

    public KafkaLagMonitoringService(
            MonitoringProperties props,
            @Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilder,
            Environment environment) {
        this.props = props;
        this.streamsBuilder = streamsBuilder;
        this.environment = environment;
    }

    /**
     * Collects lag for all configured consumer groups.
     *
     * @return map with consumer group names as keys and per-group lag details as values
     */
    public Map<String, Object> collectLagForAllGroups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thresholds", Map.of(
                "warning", props.getLagThresholdWarning(),
                "critical", props.getLagThresholdCritical()
        ));

        String bootstrapServers = resolveBootstrapServers();
        if (bootstrapServers == null) {
            result.put("error", "No bootstrap.servers configured");
            return result;
        }

        Properties adminProps = buildAdminProps(bootstrapServers);

        List<Map<String, Object>> groups = new ArrayList<>();
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            for (String groupId : props.getKafkaConsumerGroups()) {
                groups.add(collectGroupLag(adminClient, groupId));
            }
        } catch (Exception e) {
            log.warn("Failed to create Kafka AdminClient: {}", e.getMessage());
            result.put("error", "AdminClient unavailable: " + e.getMessage());
            return result;
        }

        result.put("consumerGroups", groups);
        return result;
    }

    // ── Private helpers ────────────────────────────────────────────────

    Map<String, Object> collectGroupLag(AdminClient adminClient, String groupId) {
        Map<String, Object> groupReport = new LinkedHashMap<>();
        groupReport.put("consumerGroup", groupId);

        try {
            ListConsumerGroupOffsetsResult offsetsResult =
                    adminClient.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, OffsetAndMetadata> committed =
                    offsetsResult.partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);

            if (committed == null || committed.isEmpty()) {
                groupReport.put("status", "NO_COMMITTED_OFFSETS");
                return groupReport;
            }

            // Fetch end offsets for the same partitions
            Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
            for (TopicPartition tp : committed.keySet()) {
                request.put(tp, OffsetSpec.latest());
            }
            ListOffsetsResult endResult = adminClient.listOffsets(request);

            // Compute lag per partition
            List<Map<String, Object>> partitions = new ArrayList<>();
            long totalLag = 0;
            long maxLag = 0;
            String maxLagPartition = null;
            int partitionsWithLag = 0;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committedOffset = entry.getValue().offset();
                long endOffset;
                try {
                    endOffset = endResult.partitionResult(tp).get(5, TimeUnit.SECONDS).offset();
                } catch (Exception e) {
                    log.debug("Could not get end offset for {}: {}", tp, e.getMessage());
                    continue;
                }
                long lag = Math.max(0, endOffset - committedOffset);
                totalLag += lag;
                if (lag > 0) partitionsWithLag++;
                if (lag > maxLag) {
                    maxLag = lag;
                    maxLagPartition = tp.topic() + "-" + tp.partition();
                }

                partitions.add(Map.of(
                        "topic", tp.topic(),
                        "partition", tp.partition(),
                        "committedOffset", committedOffset,
                        "endOffset", endOffset,
                        "lag", lag,
                        "severity", lagSeverity(lag)
                ));
            }

            long avgLag = partitions.isEmpty() ? 0 : totalLag / partitions.size();
            groupReport.put("totalLag", totalLag);
            groupReport.put("avgLagPerPartition", avgLag);
            groupReport.put("maxLag", maxLag);
            groupReport.put("maxLagPartition", maxLagPartition);
            groupReport.put("partitionsWithLag", partitionsWithLag);
            groupReport.put("totalPartitions", partitions.size());
            groupReport.put("severity", lagSeverity(totalLag));
            groupReport.put("partitions", partitions);

        } catch (Exception e) {
            log.warn("Failed to collect lag for group {}: {}", groupId, e.getMessage());
            groupReport.put("error", "Failed: " + e.getMessage());
        }

        return groupReport;
    }

    String lagSeverity(long lag) {
        if (lag < props.getLagThresholdWarning()) return "OK";
        if (lag < props.getLagThresholdCritical()) return "WARNING";
        return "CRITICAL";
    }

    private String resolveBootstrapServers() {
        try {
            Properties cfg = streamsBuilder.getStreamsConfiguration();
            if (cfg != null) {
                String servers = cfg.getProperty("bootstrap.servers");
                if (servers != null) return servers;
            }
        } catch (Exception e) {
            log.debug("Could not read streams bootstrap.servers: {}", e.getMessage());
        }
        return environment.getProperty("spring.kafka.bootstrap-servers");
    }

    private Properties buildAdminProps(String bootstrapServers) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        // Propagate SASL/SSL from streams config (needed for Confluent Cloud)
        try {
            Properties streamsCfg = streamsBuilder.getStreamsConfiguration();
            if (streamsCfg != null) {
                for (String key : streamsCfg.stringPropertyNames()) {
                    if (key.startsWith("security.") || key.startsWith("sasl.") || key.startsWith("ssl.")) {
                        p.put(key, streamsCfg.getProperty(key));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not copy security props: {}", e.getMessage());
        }
        return p;
    }
}
