package com.example.financialstream.runtime;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Custom Actuator endpoint exposing runtime configuration and state.
 *
 * Access: GET /actuator/runtime (management port 9090)
 *
 * Purpose: deployment verification — call this endpoint when promoting
 * code from dev → test → prod to verify all environment-specific config
 * is correct (bootstrap servers, DB URL, profiles, topics, etc.).
 *
 * Safety:
 * - All sensitive values (passwords, API keys, SASL config) are redacted by Sanitiser
 * - No external calls — all data is local/in-memory, instant response
 * - Runs on management port 9090 (cluster-internal, not exposed via SPIFFE ingress)
 * - NOT used by AKS probes — purely for human/automation inspection
 * - Cache TTL configurable via management.endpoint.runtime.cache.time-to-live
 */
@Component
@Endpoint(id = "runtime")
public class RuntimeDiscoveryEndpoint {

    private final RuntimeDiscoveryService service;

    public RuntimeDiscoveryEndpoint(RuntimeDiscoveryService service) {
        this.service = service;
    }

    @ReadOperation
    public Map<String, Object> runtime() {
        return service.discover();
    }
}
