package com.example.financialstream.controller;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import com.example.financialstream.runtime.RuntimeDiscoveryService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControllerTest {

    @Test
    void healthAndApplicationStatusExposeExpectedMetadata() {
        HealthController controller = new HealthController();

        Map<String, Object> health = controller.health().getBody();
        Map<String, Object> status = controller.applicationStatus().getBody();

        assertNotNull(health);
        assertEquals("UP", health.get("status"));
        assertEquals("kafka-streams-payment-processor", health.get("service"));
        assertNotNull(health.get("timestamp"));
        assertNotNull(status);
        assertEquals("kafka-streams-cb", status.get("application"));
        assertEquals("RUNNING", status.get("status"));
    }

    @Test
    void circuitBreakerStatusHandlesMissingAndAvailableBreaker() {
        HealthController controller = new HealthController();

        Map<String, Object> unavailable = controller.circuitBreakerStatus().getBody();

        assertNotNull(unavailable);
        assertEquals("UNKNOWN", unavailable.get("state"));

        BusinessOutcomeCircuitBreaker breaker = mock(BusinessOutcomeCircuitBreaker.class);
        when(breaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(breaker.getNextRestartDelay()).thenReturn(Duration.ofMinutes(2));
        ReflectionTestUtils.setField(controller, "circuitBreaker", breaker);

        Map<String, Object> available = controller.circuitBreakerStatus().getBody();

        assertNotNull(available);
        assertEquals("OPEN", available.get("state"));
        assertEquals(Duration.ofMinutes(2), available.get("nextRestartDelay"));
    }

    @Test
    void helloControllerUsesProvidedName() {
        assertEquals("HI Devin, How are you?", new HelloController().hello("Devin"));
    }

    @Test
    void runtimeControllerReturnsDiscoveryPayload() {
        RuntimeDiscoveryService service = mock(RuntimeDiscoveryService.class);
        Map<String, Object> payload = Map.of("state", "RUNNING");
        when(service.discover()).thenReturn(payload);

        Map<String, Object> body = new RuntimeDiscoveryController(service).runtime().getBody();

        assertSame(payload, body);
    }
}
