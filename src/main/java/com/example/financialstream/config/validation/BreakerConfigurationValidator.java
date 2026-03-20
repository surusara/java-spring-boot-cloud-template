package com.example.financialstream.config.validation;

import com.example.financialstream.circuit.BreakerControlProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Validates circuit breaker configuration for production safety.
 * Fails fast on invalid configurations to prevent silent degradation.
 */
@Component
public class BreakerConfigurationValidator implements InitializingBean {

    private final BreakerControlProperties properties;

    public BreakerConfigurationValidator(BreakerControlProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        validateThreshold();
        validateMinimumCalls();
        validateRestartDelays();
        validateTimeWindow();
    }

    private void validateThreshold() {
        float threshold = properties.getFailureRateThreshold();
        if (threshold < 1 || threshold > 100) {
            throw new IllegalArgumentException(
                "Failure rate threshold must be between 1 and 100 percent. Got: " + threshold
            );
        }
    }

    private void validateMinimumCalls() {
        int minCalls = properties.getMinimumNumberOfCalls();
        if (minCalls < 10) {
            throw new IllegalArgumentException(
                "Minimum calls must be at least 10 to avoid false positives. Got: " + minCalls
            );
        }
    }

    private void validateRestartDelays() {
        if (properties.getRestartDelays().isEmpty()) {
            throw new IllegalArgumentException("Restart delays list cannot be empty");
        }
        long firstDelayMs = properties.getRestartDelays().get(0).toMillis();
        if (firstDelayMs < 10_000) {
            throw new IllegalArgumentException(
                "First restart delay must be at least 10 seconds. Got: " + (firstDelayMs / 1000) + "s"
            );
        }
    }

    private void validateTimeWindow() {
        int windowSeconds = properties.getTimeWindowSeconds();
        if (windowSeconds < 60 || windowSeconds > 7200) {
            throw new IllegalArgumentException(
                "Time window must be between 60 and 7200 seconds. Got: " + windowSeconds + "s"
            );
        }
    }
}
