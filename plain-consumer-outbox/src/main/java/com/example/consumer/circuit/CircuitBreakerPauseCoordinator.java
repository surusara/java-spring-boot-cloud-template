package com.example.consumer.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Keeps the listener containers' paused state in sync with the enrichment circuit breaker.
 *
 * <p>Event-driven for immediacy, plus a scheduled reconcile as a safety net (covers missed events
 * and the automatic OPEN→HALF_OPEN transition):
 * <ul>
 *   <li>OPEN → pause (stop consuming while the dependency is down)</li>
 *   <li>HALF_OPEN → resume (let a few trial records flow to probe recovery)</li>
 *   <li>CLOSED → resume (normal operation)</li>
 * </ul>
 */
@Component
public class CircuitBreakerPauseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerPauseCoordinator.class);

    private final CircuitBreaker breaker;
    private final ConsumerPauseController pauseController;

    public CircuitBreakerPauseCoordinator(CircuitBreaker enrichmentCircuitBreaker,
                                          ConsumerPauseController pauseController) {
        this.breaker = enrichmentCircuitBreaker;
        this.pauseController = pauseController;
    }

    @PostConstruct
    void subscribe() {
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            log.info("🔁 Circuit breaker [{}] {} → {}", breaker.getName(),
                    event.getStateTransition().getFromState(), to);
            apply(to);
        });
    }

    /** Idempotent safety net; also picks up the automatic OPEN→HALF_OPEN transition. */
    @Scheduled(fixedDelayString = "${app.circuit-breaker.reconcile-delay-ms:5000}")
    void reconcile() {
        apply(breaker.getState());
    }

    private void apply(CircuitBreaker.State state) {
        if (state == CircuitBreaker.State.OPEN) {
            pauseController.pauseAll();
        } else {
            pauseController.resumeAll();
        }
    }
}
