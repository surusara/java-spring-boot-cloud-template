package com.example.financialstream.health;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes circuit breaker state via /actuator/health for observability ONLY.
 *
 * All states return Health.up() — breaker state is a business concern,
 * not a pod health concern. K8s probes are NOT affected:
 *   CLOSED    → UP + details (processing active)
 *   HALF_OPEN → UP + details (testing recovery)
 *   OPEN      → UP + details (paused, recovery scheduler running inside pod)
 *
 * Liveness: unaffected — pod is alive regardless of breaker state.
 * Readiness: unaffected — REST APIs still serve traffic; Kafka partition
 *            assignment is managed by consumer group protocol, not K8s readiness.
 */
@Component("circuitBreakerHealth")
public class CircuitBreakerHealthIndicator implements HealthIndicator {

    private final BusinessOutcomeCircuitBreaker breaker;

    public CircuitBreakerHealthIndicator(BusinessOutcomeCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = breaker.getState();

        return switch (state) {
            case CLOSED -> Health.up()
                    .withDetail("breaker_state", "CLOSED")
                    .withDetail("status", "Normal — processing active")
                    .build();

            case HALF_OPEN -> Health.up()
                    .withDetail("breaker_state", "HALF_OPEN")
                    .withDetail("status", "Testing — recovery in progress")
                    .build();

            case OPEN -> Health.up()
                    .withDetail("breaker_state", "OPEN")
                    .withDetail("status", "Paused — waiting for recovery")
                    .withDetail("next_restart_delay", breaker.getNextRestartDelay().toString())
                    .build();

            default -> Health.unknown()
                    .withDetail("breaker_state", state.toString())
                    .build();
        };
    }
}
