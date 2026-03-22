package com.example.financialstream.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KubernetesMonitoringService graceful-degradation paths.
 * Does not make real K8s API calls — tests the k8s-disabled (local dev) code path.
 */
class KubernetesMonitoringServiceTest {

    private KubernetesMonitoringService service;
    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();
        props.setK8sEnabled(false); // local dev mode — no real K8s calls
        service = new KubernetesMonitoringService(props);
    }

    @Test
    void getPodInfoReturnsPlaceholderWhenK8sDisabled() {
        Map<String, Object> result = service.getPodInfo();
        assertNotNull(result);
        assertTrue(result.containsKey("note"), "Should contain 'note' key when k8s disabled");
        assertTrue(result.get("note").toString().contains("k8s-enabled"), "Note should mention k8s-enabled property");
    }

    @Test
    void getDeploymentInfoReturnsPlaceholderWhenK8sDisabled() {
        Map<String, Object> result = service.getDeploymentInfo();
        assertNotNull(result);
        assertTrue(result.containsKey("note"));
    }

    @Test
    void getHpaInfoReturnsPlaceholderWhenK8sDisabled() {
        Map<String, Object> result = service.getHpaInfo();
        assertNotNull(result);
        assertTrue(result.containsKey("note"));
    }
}
