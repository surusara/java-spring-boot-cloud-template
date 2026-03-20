package com.example.financialstream.circuit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.circuit-breaker")
public class BreakerControlProperties {

    private float failureRateThreshold = 20.0f;
    private int timeWindowSeconds = 1800;
    private int minimumNumberOfCalls = 100;
    private int permittedCallsInHalfOpenState = 20;
    private Duration maxWaitInHalfOpenState = Duration.ofMinutes(2);
    private List<Duration> restartDelays = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(3),
            Duration.ofMinutes(5), Duration.ofMinutes(7), Duration.ofMinutes(10));

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(int timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
        this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
    }

    public Duration getMaxWaitInHalfOpenState() {
        return maxWaitInHalfOpenState;
    }

    public void setMaxWaitInHalfOpenState(Duration maxWaitInHalfOpenState) {
        this.maxWaitInHalfOpenState = maxWaitInHalfOpenState;
    }

    public List<Duration> getRestartDelays() {
        return restartDelays;
    }

    public void setRestartDelays(List<Duration> restartDelays) {
        this.restartDelays = restartDelays;
    }
}
