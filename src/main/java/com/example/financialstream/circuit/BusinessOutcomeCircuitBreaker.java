package com.example.financialstream.circuit;

import com.example.financialstream.metrics.CircuitBreakerMetrics;
import com.example.financialstream.model.ProcessingResult;
import com.example.financialstream.model.ProcessingStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BusinessOutcomeCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(BusinessOutcomeCircuitBreaker.class);

    private final BreakerControlProperties properties;
    private final StreamLifecycleController lifecycleController;
    private final AtomicInteger openCount = new AtomicInteger(0);

    @Autowired(required = false)
    private CircuitBreakerMetrics metrics;

    private CircuitBreaker circuitBreaker;
    private volatile Duration nextRestartDelay = Duration.ofMinutes(1);

    public BusinessOutcomeCircuitBreaker(BreakerControlProperties properties,
                                         StreamLifecycleController lifecycleController) {
        this.properties = properties;
        this.lifecycleController = lifecycleController;
    }

    @PostConstruct
    void init() {
        log.info("Initializing circuit breaker: threshold={}%, window={}s, min-calls={}",
            properties.getFailureRateThreshold(),
            properties.getTimeWindowSeconds(),
            properties.getMinimumNumberOfCalls()
        );

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getFailureRateThreshold())
                .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(properties.getTimeWindowSeconds())
                .waitDurationInOpenState(properties.getRestartDelays().getFirst())
                .permittedNumberOfCallsInHalfOpenState(properties.getPermittedCallsInHalfOpenState())
                .maxWaitDurationInHalfOpenState(properties.getMaxWaitInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(ex -> ex instanceof SoftBusinessFailureException)
                .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("payments-business-soft-failure");
        this.circuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                int currentOpen = openCount.incrementAndGet();
                List<Duration> delays = properties.getRestartDelays();
                int index = Math.min(currentOpen - 1, delays.size() - 1);
                nextRestartDelay = delays.get(index);
                log.error("🔴 BREAKER OPEN (attempt #{}): Soft failure rate exceeded {}%. Next recovery: {}",
                    currentOpen,
                    properties.getFailureRateThreshold(),
                    nextRestartDelay
                );
                
                // Record metrics before stopping stream
                if (metrics != null) {
                    metrics.recordOpenEvent();
                }
                
                lifecycleController.stopStream();
                
            } else if (event.getStateTransition().getToState() == CircuitBreaker.State.CLOSED) {
                openCount.set(0);
                nextRestartDelay = properties.getRestartDelays().getFirst();
                log.info("✅ BREAKER CLOSED: Soft failure rate normalized. Resuming normal processing.");
            } else if (event.getStateTransition().getToState() == CircuitBreaker.State.HALF_OPEN) {
                log.warn("🟡 BREAKER HALF-OPEN: Testing recovery with {} permitted calls", 
                    properties.getPermittedCallsInHalfOpenState());
            }
        });
    }

    public void record(ProcessingResult result) {
        if (result.status() == ProcessingStatus.SUCCESS) {
            circuitBreaker.onSuccess(0, TimeUnit.MILLISECONDS);
        } else {
            circuitBreaker.onError(0, TimeUnit.MILLISECONDS,
                    new SoftBusinessFailureException(result.code(), result.message()));
        }
    }

    public boolean tryAcquirePermission() {
        return circuitBreaker.tryAcquirePermission();
    }

    public void moveToHalfOpenAndRestart() {
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            circuitBreaker.transitionToHalfOpenState();
        }
        lifecycleController.startStream();
    }

    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }

    public Duration getNextRestartDelay() {
        return nextRestartDelay;
    }

    public static final class SoftBusinessFailureException extends RuntimeException {
        public SoftBusinessFailureException(String code, String message) {
            super(code + ":" + message);
        }
    }
}
