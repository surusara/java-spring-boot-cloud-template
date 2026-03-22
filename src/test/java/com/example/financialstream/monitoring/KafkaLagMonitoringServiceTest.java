package com.example.financialstream.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KafkaLagMonitoringService lag severity calculation.
 */
class KafkaLagMonitoringServiceTest {

    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();
        props.setLagThresholdWarning(100);
        props.setLagThresholdCritical(500);
    }

    @Test
    void lagSeverityOkBelowWarningThreshold() {
        // Use a test subclass to access the package-private lagSeverity method
        TestKafkaLagMonitoringService service = new TestKafkaLagMonitoringService(props);
        assertEquals("OK", service.lagSeverity(0));
        assertEquals("OK", service.lagSeverity(99));
    }

    @Test
    void lagSeverityWarningBetweenThresholds() {
        TestKafkaLagMonitoringService service = new TestKafkaLagMonitoringService(props);
        assertEquals("WARNING", service.lagSeverity(100));
        assertEquals("WARNING", service.lagSeverity(499));
    }

    @Test
    void lagSeverityCriticalAtOrAboveCriticalThreshold() {
        TestKafkaLagMonitoringService service = new TestKafkaLagMonitoringService(props);
        assertEquals("CRITICAL", service.lagSeverity(500));
        assertEquals("CRITICAL", service.lagSeverity(100000));
    }

    @Test
    void lagSeverityCustomThresholds() {
        props.setLagThresholdWarning(50);
        props.setLagThresholdCritical(200);
        TestKafkaLagMonitoringService service = new TestKafkaLagMonitoringService(props);
        assertEquals("OK", service.lagSeverity(49));
        assertEquals("WARNING", service.lagSeverity(50));
        assertEquals("WARNING", service.lagSeverity(199));
        assertEquals("CRITICAL", service.lagSeverity(200));
    }

    /** Minimal subclass to expose package-private lagSeverity for testing. */
    private static class TestKafkaLagMonitoringService extends KafkaLagMonitoringService {
        TestKafkaLagMonitoringService(MonitoringProperties props) {
            super(props, null, null);
        }

        @Override
        String lagSeverity(long lag) {
            return super.lagSeverity(lag);
        }
    }
}
