package com.example.consumer.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerPauseCoordinatorTest {

    @Mock private ConsumerPauseController pauseController;

    private CircuitBreaker breaker;
    private CircuitBreakerPauseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        breaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("enrichment");
        coordinator = new CircuitBreakerPauseCoordinator(breaker, pauseController);
        coordinator.subscribe();
    }

    @Test
    void openTransitionPausesConsumption() {
        breaker.transitionToOpenState();
        verify(pauseController, atLeastOnce()).pauseAll();
    }

    @Test
    void recoveryResumesConsumption() {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();
        verify(pauseController, atLeastOnce()).resumeAll();
    }

    @Test
    void reconcileResumesWhenClosed() {
        coordinator.reconcile();
        verify(pauseController).resumeAll();
    }
}
