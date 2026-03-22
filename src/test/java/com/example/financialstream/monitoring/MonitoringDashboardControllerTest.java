package com.example.financialstream.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MonitoringDashboardController REST endpoints.
 * Uses a stub service — no Spring context required.
 */
class MonitoringDashboardControllerTest {

    private MonitoringDashboardController controller;

    @BeforeEach
    void setUp() {
        MonitoringProperties props = new MonitoringProperties();
        props.setSseHeartbeatMs(60000); // long interval so scheduler doesn't run during tests

        StubDashboardService stubService = new StubDashboardService(props);
        controller = new MonitoringDashboardController(stubService, props);
    }

    @Test
    void dashboardEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.dashboard();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().containsKey("timestamp"));
    }

    @Test
    void podsEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.pods();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
    }

    @Test
    void kafkaLagEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.kafkaLag();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
    }

    @Test
    void kedaEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.keda();
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void circuitBreakerEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.circuitBreaker();
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void streamsEndpointReturns200() {
        ResponseEntity<Map<String, Object>> resp = controller.streams();
        assertEquals(200, resp.getStatusCode().value());
    }

    // ── Stub ──────────────────────────────────────────────────────────

    private static class StubDashboardService extends MonitoringDashboardService {
        StubDashboardService(MonitoringProperties props) {
            super(null, new KubernetesMonitoringService(props), null, props);
        }

        @Override
        public Map<String, Object> getDashboard() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", "2026-01-01T00:00:00Z");
            m.put("refreshIntervalMs", 5000L);
            return m;
        }

        @Override
        public Map<String, Object> getPods() { return Map.of("count", 2); }

        @Override
        public Map<String, Object> getKafkaLag() { return Map.of("consumerGroups", java.util.List.of()); }

        @Override
        public Map<String, Object> getKeda() { return Map.of(); }

        @Override
        public Map<String, Object> getCircuitBreaker() { return Map.of("state", "CLOSED"); }

        @Override
        public Map<String, Object> getStreams() { return Map.of("kafkaStreams", Map.of("state", "RUNNING")); }

        @Override
        public Map<String, Object> getCachedDashboard() { return getDashboard(); }
    }
}
