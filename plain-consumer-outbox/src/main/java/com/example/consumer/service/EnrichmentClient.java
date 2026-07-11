package com.example.consumer.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the external static-data service to enrich a payment (e.g. resolve a risk tier).
 * The call is wrapped by the circuit breaker: when the dependency is failing, the breaker OPENs
 * and {@link #lookupRiskTier} throws {@code CallNotPermittedException}, which the coordinator
 * uses to pause consumption. The in-flight record is not acked and is re-delivered after recovery.
 */
@Component
public class EnrichmentClient {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentClient.class);

    private final CircuitBreaker breaker;
    private final RestClient restClient;

    public EnrichmentClient(CircuitBreaker enrichmentCircuitBreaker,
                            RestClient.Builder restClientBuilder,
                            @Value("${app.enrichment.base-url:http://static-data-service/api}") String baseUrl) {
        this.breaker = enrichmentCircuitBreaker;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public String lookupRiskTier(String accountId) {
        return breaker.executeSupplier(() -> {
            RiskTierResponse response = restClient.get()
                    .uri("/accounts/{accountId}/risk-tier", accountId)
                    .retrieve()
                    .body(RiskTierResponse.class);
            if (response == null || response.riskTier() == null) {
                throw new IllegalStateException("Empty risk-tier response for account " + accountId);
            }
            log.debug("Enriched account {} → riskTier={}", accountId, response.riskTier());
            return response.riskTier();
        });
    }

    public record RiskTierResponse(String riskTier) {
    }
}
