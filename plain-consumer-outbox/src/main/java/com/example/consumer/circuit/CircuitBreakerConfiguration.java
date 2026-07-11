package com.example.consumer.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfiguration {

    public static final String ENRICHMENT = "enrichment";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${app.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${app.circuit-breaker.sliding-window-seconds:30}") int slidingWindowSeconds,
            @Value("${app.circuit-breaker.minimum-number-of-calls:10}") int minimumNumberOfCalls,
            @Value("${app.circuit-breaker.wait-duration-open-seconds:60}") int waitDurationOpenSeconds,
            @Value("${app.circuit-breaker.permitted-calls-half-open:3}") int permittedCallsHalfOpen) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(slidingWindowSeconds)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationOpenSeconds))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsHalfOpen)
                // Auto OPEN->HALF_OPEN after the wait duration. The reconciler then resumes the
                // container so a few trial records can flow; success closes it, failure re-opens it.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker enrichmentCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(ENRICHMENT);
    }
}
