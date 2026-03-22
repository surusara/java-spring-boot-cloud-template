package com.example.financialstream.monitoring;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import com.example.financialstream.runtime.RuntimeDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregates all monitoring data into a single dashboard snapshot.
 *
 * <p>Data sources:
 * <ul>
 *   <li>{@link RuntimeDiscoveryService} — Kafka Streams state, JVM, pod, config</li>
 *   <li>{@link KubernetesMonitoringService} — Pod list, deployment replicas, HPA status</li>
 *   <li>{@link KafkaLagMonitoringService} — Per-partition consumer lag breakdown</li>
 *   <li>{@link BusinessOutcomeCircuitBreaker} — Circuit breaker state and failure rate</li>
 * </ul>
 *
 * <p>Uses {@link AtomicReference} for the cached snapshot so concurrent SSE clients
 * and REST callers see a consistent view without locking.
 */
@Service
public class MonitoringDashboardService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringDashboardService.class);

    private final RuntimeDiscoveryService runtimeDiscoveryService;
    private final KubernetesMonitoringService kubernetesMonitoringService;
    private final KafkaLagMonitoringService kafkaLagMonitoringService;
    private final MonitoringProperties props;

    @Autowired(required = false)
    private BusinessOutcomeCircuitBreaker circuitBreaker;

    /** Last computed dashboard snapshot — thread-safe via AtomicReference. */
    private final AtomicReference<Map<String, Object>> cachedDashboard = new AtomicReference<>();

    public MonitoringDashboardService(
            RuntimeDiscoveryService runtimeDiscoveryService,
            KubernetesMonitoringService kubernetesMonitoringService,
            KafkaLagMonitoringService kafkaLagMonitoringService,
            MonitoringProperties props) {
        this.runtimeDiscoveryService = runtimeDiscoveryService;
        this.kubernetesMonitoringService = kubernetesMonitoringService;
        this.kafkaLagMonitoringService = kafkaLagMonitoringService;
        this.props = props;
    }

    /**
     * Returns the full dashboard snapshot, refreshing from all data sources.
     * Any section that fails is replaced with an error indicator so the rest
     * of the dashboard continues to work (graceful degradation).
     */
    public Map<String, Object> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("timestamp", Instant.now().toString());
        dashboard.put("refreshIntervalMs", props.getRefreshIntervalMs());

        // ── Runtime & JVM (from existing RuntimeDiscoveryService) ──────
        Map<String, Object> runtime = safeCollect("runtime", runtimeDiscoveryService::discover);
        dashboard.put("meta", runtime.get("meta"));
        dashboard.put("pod", runtime.get("pod"));
        dashboard.put("jvm", runtime.get("jvm"));
        dashboard.put("kafkaStreams", runtime.get("kafkaStreams"));

        // ── Circuit Breaker ─────────────────────────────────────────────
        dashboard.put("circuitBreaker", safeCollect("circuitBreaker", this::collectCircuitBreakerState));

        // ── Kafka Lag (enhanced, per-partition breakdown) ───────────────
        dashboard.put("kafkaLag", safeCollect("kafkaLag", kafkaLagMonitoringService::collectLagForAllGroups));

        // ── Kubernetes (pods, deployment, HPA) ──────────────────────────
        Map<String, Object> k8s = new LinkedHashMap<>();
        k8s.put("pods", safeCollect("k8s.pods", kubernetesMonitoringService::getPodInfo));
        k8s.put("deployment", safeCollect("k8s.deployment", kubernetesMonitoringService::getDeploymentInfo));
        k8s.put("hpa", safeCollect("k8s.hpa", kubernetesMonitoringService::getHpaInfo));
        dashboard.put("scaling", k8s);

        cachedDashboard.set(dashboard);
        return dashboard;
    }

    /** Returns pods section only. */
    public Map<String, Object> getPods() {
        return safeCollect("k8s.pods", kubernetesMonitoringService::getPodInfo);
    }

    /** Returns Kafka lag section only. */
    public Map<String, Object> getKafkaLag() {
        return safeCollect("kafkaLag", kafkaLagMonitoringService::collectLagForAllGroups);
    }

    /** Returns KEDA/HPA scaling section only. */
    public Map<String, Object> getKeda() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployment", safeCollect("k8s.deployment", kubernetesMonitoringService::getDeploymentInfo));
        result.put("hpa", safeCollect("k8s.hpa", kubernetesMonitoringService::getHpaInfo));
        return result;
    }

    /** Returns circuit breaker state only. */
    public Map<String, Object> getCircuitBreaker() {
        return safeCollect("circuitBreaker", this::collectCircuitBreakerState);
    }

    /** Returns Kafka Streams health only. */
    public Map<String, Object> getStreams() {
        Map<String, Object> runtime = safeCollect("runtime", runtimeDiscoveryService::discover);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kafkaStreams", runtime.get("kafkaStreams"));
        return result;
    }

    /** Returns the last cached snapshot without re-querying all sources. */
    public Map<String, Object> getCachedDashboard() {
        Map<String, Object> cached = cachedDashboard.get();
        return cached != null ? cached : getDashboard();
    }

    // ── Private helpers ────────────────────────────────────────────────

    private Map<String, Object> collectCircuitBreakerState() {
        Map<String, Object> cb = new LinkedHashMap<>();
        if (circuitBreaker == null) {
            cb.put("state", "NOT_INITIALIZED");
            return cb;
        }
        cb.put("state", circuitBreaker.getState().name());
        cb.put("nextRestartDelay", circuitBreaker.getNextRestartDelay() != null
                ? circuitBreaker.getNextRestartDelay().toString() : null);
        cb.put("openCount", circuitBreaker.getOpenCount());
        return cb;
    }

    @FunctionalInterface
    private interface DataSupplier {
        Map<String, Object> get();
    }

    private Map<String, Object> safeCollect(String section, DataSupplier supplier) {
        try {
            Map<String, Object> data = supplier.get();
            return data != null ? data : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("Failed to collect monitoring section '{}': {}", section, e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Collection failed: " + e.getMessage());
            return error;
        }
    }
}
