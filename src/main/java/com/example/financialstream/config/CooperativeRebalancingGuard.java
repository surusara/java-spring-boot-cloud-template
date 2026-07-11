package com.example.financialstream.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup guard that runs {@link KafkaStreamsConfig#validateCooperativeRebalancingConfig}
 * during context initialization (before the streams instance starts).
 *
 * <p>Without this, the guard was defined but never invoked at runtime, so a
 * {@code spring.kafka.streams.properties.upgrade.from=2.3/2.4} misconfiguration — which forces the
 * eager rebalance protocol and causes rebalance storms + duplicates on every scale event — would
 * go undetected until it caused a production incident.
 */
@Component
public class CooperativeRebalancingGuard {

    private final Environment environment;

    public CooperativeRebalancingGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        KafkaStreamsConfig.validateCooperativeRebalancingConfig(environment);
    }
}
