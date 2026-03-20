package com.example.financialstream.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CircuitBreakerMetrics {

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker> breakerProvider;
    private final AtomicInteger openCount = new AtomicInteger(0);

    public CircuitBreakerMetrics(MeterRegistry meterRegistry, 
                                @Autowired(required = false) ObjectProvider<com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker> breakerProvider) {
        this.meterRegistry = meterRegistry;
        this.breakerProvider = breakerProvider;
        registerMetrics();
    }

    private void registerMetrics() {
        // Current state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN
        meterRegistry.gauge(
            "circuit_breaker_current_state",
            Tags.of("breaker", "payments-business-soft-failure"),
            this,
            cb -> {
                var breaker = breakerProvider.getIfAvailable();
                if (breaker == null) return -1;
                var state = breaker.getState();
                return switch (state) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> -1;
                };
            }
        );

        // Next restart delay (in seconds)
        meterRegistry.gauge(
            "circuit_breaker_next_restart_delay_seconds",
            Tags.of("breaker", "payments-business-soft-failure"),
            this,
            cb -> {
                var breaker = breakerProvider.getIfAvailable();
                if (breaker == null) return 0.0;
                return (double) breaker.getNextRestartDelay().getSeconds();
            }
        );

        // Total number of times breaker opened (cumulative)
        meterRegistry.gauge(
            "circuit_breaker_open_count_total",
            Tags.of("breaker", "payments-business-soft-failure"),
            openCount,
            AtomicInteger::get
        );
    }

    public void recordOpenEvent() {
        openCount.incrementAndGet();
    }
}

