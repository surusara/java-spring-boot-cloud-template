package com.example.financialstream.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that MonitoringProperties binds defaults correctly.
 */
class MonitoringPropertiesTest {

    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();
    }

    @Test
    void defaultsAreCorrect() {
        assertTrue(props.isEnabled());
        assertEquals(5000L, props.getRefreshIntervalMs());
        assertEquals(5000L, props.getSseHeartbeatMs());
        assertEquals(3000L, props.getK8sApiTimeoutMs());
        assertEquals("financial-streams", props.getNamespace());
        assertEquals("payments-stream", props.getDeploymentName());
        assertEquals("app=payments-stream", props.getPodLabelSelector());
        assertEquals(100L, props.getLagThresholdWarning());
        assertEquals(500L, props.getLagThresholdCritical());
        assertFalse(props.isK8sEnabled());
        assertEquals(List.of("payments-stream-v1", "payments-exception-v1"), props.getKafkaConsumerGroups());
    }

    @Test
    void settersAndGettersWork() {
        props.setEnabled(false);
        assertFalse(props.isEnabled());

        props.setK8sEnabled(true);
        assertTrue(props.isK8sEnabled());

        props.setNamespace("test-ns");
        assertEquals("test-ns", props.getNamespace());

        props.setLagThresholdWarning(200L);
        props.setLagThresholdCritical(1000L);
        assertEquals(200L, props.getLagThresholdWarning());
        assertEquals(1000L, props.getLagThresholdCritical());

        props.setKafkaConsumerGroups(List.of("my-group"));
        assertEquals(List.of("my-group"), props.getKafkaConsumerGroups());
    }
}
