package com.example.financialstream.metrics;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CircuitBreakerMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesFallbackValuesWhenBreakerIsUnavailable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<BusinessOutcomeCircuitBreaker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        new CircuitBreakerMetrics(registry, provider);

        assertEquals(-1.0, gauge(registry, "circuit_breaker_current_state"));
        assertEquals(0.0, gauge(registry, "circuit_breaker_next_restart_delay_seconds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesBreakerStateDelayAndOpenCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<BusinessOutcomeCircuitBreaker> provider = mock(ObjectProvider.class);
        BusinessOutcomeCircuitBreaker breaker = mock(BusinessOutcomeCircuitBreaker.class);
        AtomicReference<CircuitBreaker.State> state = new AtomicReference<>(CircuitBreaker.State.CLOSED);
        when(provider.getIfAvailable()).thenReturn(breaker);
        when(breaker.getState()).thenAnswer(invocation -> state.get());
        when(breaker.getNextRestartDelay()).thenReturn(Duration.ofSeconds(90));

        CircuitBreakerMetrics metrics = new CircuitBreakerMetrics(registry, provider);

        assertEquals(0.0, gauge(registry, "circuit_breaker_current_state"));
        state.set(CircuitBreaker.State.OPEN);
        assertEquals(1.0, gauge(registry, "circuit_breaker_current_state"));
        state.set(CircuitBreaker.State.HALF_OPEN);
        assertEquals(2.0, gauge(registry, "circuit_breaker_current_state"));
        state.set(CircuitBreaker.State.DISABLED);
        assertEquals(-1.0, gauge(registry, "circuit_breaker_current_state"));
        assertEquals(90.0, gauge(registry, "circuit_breaker_next_restart_delay_seconds"));

        metrics.recordOpenEvent();
        metrics.recordOpenEvent();

        assertEquals(2.0, gauge(registry, "circuit_breaker_open_count_total"));
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
