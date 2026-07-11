package com.example.financialstream.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for the Actuator management endpoints (management port 9090).
 *
 * <p>Design:
 * <ul>
 *   <li><b>Business port (8080):</b> left fully permissive here — it is protected at the
 *       network layer by the service mesh (Istio mTLS / SPIFFE), consistent with the rest
 *       of the project.</li>
 *   <li><b>Actuator, {@code prod} profile:</b> every endpoint except the health/probe group
 *       requires a valid OIDC bearer token validated against the trusted issuer. This lets
 *       developers authenticate to inspect production issues (metrics, prometheus, runtime,
 *       monitoring) while kubelet liveness/readiness probes and Prometheus health scrapes
 *       keep working unauthenticated. Combined with
 *       {@code management.endpoint.health.show-details=when-authorized}, health detail is
 *       only rendered for authenticated callers.</li>
 *   <li><b>Actuator, non-{@code prod} profiles:</b> open, so local dev/test needs no IdP.</li>
 * </ul>
 *
 * <p>Requires {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} to be set in prod
 * (see {@code application-prod.yml}, sourced from env var {@code OIDC_ISSUER_URI}).
 */
@Configuration
@EnableWebSecurity
public class ActuatorSecurityConfig {

    /**
     * Actuator chain for prod: OIDC-authenticated, probes stay public.
     */
    @Bean
    @Order(1)
    @Profile("prod")
    SecurityFilterChain securedActuatorChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth
                        // Kubelet probes + Prometheus health scrape must remain unauthenticated.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        // Everything else (metrics, prometheus, runtime, monitoring, ...) needs a token.
                        // Tighten to a scope if desired, e.g. .hasAuthority("SCOPE_actuator.read").
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Actuator chain for local dev/test: fully open (no IdP available locally).
     */
    @Bean
    @Order(1)
    @Profile("!prod")
    SecurityFilterChain openActuatorChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Business endpoints (port 8080) — unchanged, mesh-protected.
     */
    @Bean
    @Order(2)
    SecurityFilterChain businessChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
