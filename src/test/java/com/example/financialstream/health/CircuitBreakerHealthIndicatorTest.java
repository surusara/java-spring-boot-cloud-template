package com.example.financialstream.health;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CircuitBreakerHealthIndicatorTest {

    @Test
    void reportsClosedBreakerAsHealthyAndActive() {
        BusinessOutcomeCircuitBreaker breaker = breakerInState(CircuitBreaker.State.CLOSED);

        Health health = new CircuitBreakerHealthIndicator(breaker).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("CLOSED", health.getDetails().get("breaker_state"));
        assertEquals("Normal — processing active", health.getDetails().get("status"));
    }

    @Test
    void reportsHalfOpenBreakerAsHealthyAndRecovering() {
        BusinessOutcomeCircuitBreaker breaker = breakerInState(CircuitBreaker.State.HALF_OPEN);

        Health health = new CircuitBreakerHealthIndicator(breaker).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("HALF_OPEN", health.getDetails().get("breaker_state"));
        assertEquals("Testing — recovery in progress", health.getDetails().get("status"));
    }

    @Test
    void reportsOpenBreakerAsHealthyWithRestartDelay() {
        BusinessOutcomeCircuitBreaker breaker = breakerInState(CircuitBreaker.State.OPEN);
        when(breaker.getNextRestartDelay()).thenReturn(Duration.ofMinutes(5));

        Health health = new CircuitBreakerHealthIndicator(breaker).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("OPEN", health.getDetails().get("breaker_state"));
        assertEquals("PT5M", health.getDetails().get("next_restart_delay"));
    }

    @Test
    void reportsUnsupportedStateAsUnknown() {
        BusinessOutcomeCircuitBreaker breaker = breakerInState(CircuitBreaker.State.DISABLED);

        Health health = new CircuitBreakerHealthIndicator(breaker).health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("DISABLED", health.getDetails().get("breaker_state"));
    }

    private BusinessOutcomeCircuitBreaker breakerInState(CircuitBreaker.State state) {
        BusinessOutcomeCircuitBreaker breaker = mock(BusinessOutcomeCircuitBreaker.class);
        when(breaker.getState()).thenReturn(state);
        return breaker;
    }
}
