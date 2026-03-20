package com.example.financialstream.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production-grade recovery scheduler for circuit breaker.
 * Manages recovery attempts with configurable backoff strategy.
 */
@Component
public class CircuitBreakerRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRecoveryScheduler.class);

    private final BusinessOutcomeCircuitBreaker breaker;
    private final StreamLifecycleController lifecycleController;
    private final AtomicReference<Instant> nextAttempt = new AtomicReference<>();

    public CircuitBreakerRecoveryScheduler(BusinessOutcomeCircuitBreaker breaker,
                                           StreamLifecycleController lifecycleController) {
        this.breaker = breaker;
        this.lifecycleController = lifecycleController;
    }

    @Scheduled(fixedDelayString = "${app.circuit-breaker.scheduler-delay-ms:5000}")
    public void scheduleRecovery() {
        try {
            CircuitBreaker.State currentState = breaker.getState();

            // Setup recovery attempt schedule when breaker opens
            if (currentState == CircuitBreaker.State.OPEN && lifecycleController.isStoppedByBreaker()) {
                if (nextAttempt.compareAndSet(null, Instant.now().plus(breaker.getNextRestartDelay()))) {
                    log.info("⏱️  Recovery scheduled in {}. Waiting...", breaker.getNextRestartDelay());
                }
            }

            // Execute recovery when scheduled time arrives
            Instant due = nextAttempt.get();
            if (due != null && !Instant.now().isBefore(due) && lifecycleController.isStoppedByBreaker()) {
                nextAttempt.set(null);
                log.info("🟡 Recovery time reached - transitioning to HALF_OPEN state");
                breaker.moveToHalfOpenAndRestart();
            }

        } catch (Exception ex) {
            log.error("Error in recovery scheduler", ex);
            // Continue scheduling; don't let scheduler crash
        }
    }
}
