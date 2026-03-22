package com.example.financialstream.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MonitoringDashboardService graceful degradation.
 * Uses stub implementations of all dependencies so no Kafka/K8s connectivity is needed.
 */
class MonitoringDashboardServiceTest {

    private MonitoringDashboardService service;
    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();

        // Stub runtime discovery service
        StubRuntimeDiscoveryService runtime = new StubRuntimeDiscoveryService();

        // Stub k8s monitoring (k8s disabled — returns placeholders)
        MonitoringProperties k8sProps = new MonitoringProperties();
        k8sProps.setK8sEnabled(false);
        KubernetesMonitoringService k8sService = new KubernetesMonitoringService(k8sProps);

        // Stub kafka lag (passes null streams factory — will fail gracefully)
        StubKafkaLagMonitoringService lagService = new StubKafkaLagMonitoringService(props);

        service = new MonitoringDashboardService(runtime, k8sService, lagService, props);
    }

    @Test
    void getDashboardReturnsTimestamp() {
        Map<String, Object> dashboard = service.getDashboard();
        assertNotNull(dashboard);
        assertTrue(dashboard.containsKey("timestamp"), "Dashboard must contain 'timestamp'");
        assertNotNull(dashboard.get("timestamp"));
    }

    @Test
    void getDashboardContainsAllSections() {
        Map<String, Object> dashboard = service.getDashboard();
        assertTrue(dashboard.containsKey("scaling"),       "Should have 'scaling' section");
        assertTrue(dashboard.containsKey("kafkaLag"),      "Should have 'kafkaLag' section");
        assertTrue(dashboard.containsKey("circuitBreaker"),"Should have 'circuitBreaker' section");
    }

    @Test
    void getDashboardHasRefreshInterval() {
        Map<String, Object> dashboard = service.getDashboard();
        assertEquals(props.getRefreshIntervalMs(), dashboard.get("refreshIntervalMs"));
    }

    @Test
    void getCachedDashboardReturnsSameData() {
        service.getDashboard(); // populate cache
        Map<String, Object> cached = service.getCachedDashboard();
        assertNotNull(cached);
        assertTrue(cached.containsKey("timestamp"));
    }

    @Test
    void getPodsReturnsSomething() {
        Map<String, Object> pods = service.getPods();
        assertNotNull(pods);
    }

    @Test
    void getKafkaLagReturnsSomething() {
        Map<String, Object> lag = service.getKafkaLag();
        assertNotNull(lag);
    }

    @Test
    void getKedaReturnsSomething() {
        Map<String, Object> keda = service.getKeda();
        assertNotNull(keda);
    }

    @Test
    void getCircuitBreakerReturnsNotInitialized() {
        Map<String, Object> cb = service.getCircuitBreaker();
        assertNotNull(cb);
        assertEquals("NOT_INITIALIZED", cb.get("state"));
    }

    @Test
    void getStreamsReturnsSomething() {
        Map<String, Object> streams = service.getStreams();
        assertNotNull(streams);
    }

    // ── Stubs ─────────────────────────────────────────────────────────

    /** Stub that returns minimal runtime data without Kafka connectivity. */
    private static class StubRuntimeDiscoveryService extends com.example.financialstream.runtime.RuntimeDiscoveryService {
        StubRuntimeDiscoveryService() {
            super(null, null, null, null);
        }

        @Override
        public Map<String, Object> discover() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("meta", Map.of("application", "test-app", "profiles", new String[]{"test"}));
            m.put("pod", Map.of("podName", "test-pod"));
            m.put("jvm", Map.of("heapUsedMB", 100, "heapMaxMB", 512));
            m.put("kafkaStreams", Map.of("state", "RUNNING", "threads", java.util.List.of()));
            return m;
        }
    }

    /** Stub that returns empty lag data without Kafka connectivity. */
    private static class StubKafkaLagMonitoringService extends KafkaLagMonitoringService {
        StubKafkaLagMonitoringService(MonitoringProperties props) {
            super(props, null, null);
        }

        @Override
        public Map<String, Object> collectLagForAllGroups() {
            return Map.of("consumerGroups", java.util.List.of());
        }
    }
}
