# 03 — Health Probes & Actuator Settings

> **Assignee:** _______________  
> **Scope:** K8s liveness/readiness/startup probes, Spring Boot Actuator config, separate management port, Prometheus metrics  
> **Prereq:** Spring Boot app with `spring-boot-starter-actuator` dependency  

---

## Architecture: Two Ports, Two Concerns

```
Port 8080 (server.port)           Port 9090 (management.server.port)
┌──────────────────────┐          ┌──────────────────────────────┐
│  Business APIs       │          │  Actuator Endpoints          │
│  - REST controllers  │          │  - /actuator/health/liveness │
│  - Swagger UI        │          │  - /actuator/health/readiness│
│  - Behind mTLS/SPIFFE│          │  - /actuator/prometheus      │
│  - context-path:     │          │  - /actuator/info            │
│    /api/fs           │          │  - NO mTLS required          │
└──────────────────────┘          │  - NO context-path           │
                                  └──────────────────────────────┘
```

**Why separate ports?**
1. Kubelet probes can't present mTLS certs → must hit a port without SPIFFE
2. Prometheus scraper needs direct access without auth
3. Business APIs stay fully protected on 8080
4. Financial industry standard (Swiss banks, EMEA)

---

## Maven Dependencies

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
    <!-- Version managed by Spring Boot parent BOM — don't pin -->
</dependency>
```

Actuator + Micrometer are included automatically. Prometheus format is built-in.

---

## application.yml — Actuator Config (with inline explanations)

```yaml
server:
  port: 8080                        # Business APIs
  servlet:
    context-path: /api/fs           # All REST controllers serve under /api/fs/*

management:
  # --- Separate Management Port ---
  # Actuator runs on 9090, completely isolated from business APIs on 8080.
  # Kubelet probes and Prometheus hit this port directly — no mTLS/SPIFFE needed.
  # NO context-path on management port (actuator paths start at /).
  server:
    port: 9090

  # --- Endpoint Exposure ---
  # Only expose what's needed. Don't expose env, beans, configprops (security risk).
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
        # WHY these 4:
        #   health     → K8s probes (liveness, readiness)
        #   info       → App metadata (version, git commit)
        #   metrics    → Internal metrics (JVM, Kafka, custom)
        #   prometheus → Prometheus-format scraping endpoint

  # --- Health Endpoint Details ---
  endpoint:
    health:
      # Enables /actuator/health/liveness and /actuator/health/readiness
      # These are the endpoints K8s probes hit.
      probes:
        enabled: true               # REQUIRED for K8s probe endpoints to exist

      # Show full component details in health response JSON.
      # Useful for debugging — see exactly which component is DOWN.
      # In production, consider "when-authorized" if management port is exposed externally.
      show-details: always           # Options: never, when-authorized, always
      show-components: always        # Show individual health indicator statuses

  # --- Health Groups ---
  health:
    # Liveness group: Is the pod alive? Should K8s RESTART it?
    # Only checks for unrecoverable JVM/app states.
    # Kafka broker down is NOT a reason to restart — the pod is still alive.
    livenessState:
      enabled: true                  # Enables /actuator/health/liveness

    # Readiness group: Can the pod receive traffic?
    # In our architecture, readiness is NOT affected by circuit breaker state
    # because Kafka manages partition assignment independently of K8s readiness.
    readinessState:
      enabled: true                  # Enables /actuator/health/readiness

    # Kafka health check: Verifies broker connectivity.
    # Contributes to OVERALL health (/actuator/health), NOT to liveness.
    # If Kafka is down: overall health shows DOWN, but liveness stays UP (no restart).
    kafka:
      enabled: true
```

---

## K8s Probe Configuration

### Three Probes, Three Purposes

```yaml
# k8s-manifest.yaml — container spec (excerpt)
containers:
  - name: payments-stream
    ports:
      - name: http
        containerPort: 8080           # Business APIs
      - name: management
        containerPort: 9090           # Actuator (probes + metrics)

    # ┌─────────────────────────────────────────────────────────────┐
    # │ STARTUP PROBE                                              │
    # │ Purpose: Give the app time to start before liveness kicks  │
    # │ in. Without this, liveness would kill slow-starting pods.  │
    # │ Active ONLY during startup — disabled after first success. │
    # └─────────────────────────────────────────────────────────────┘
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: management              # Port 9090 — no context-path
      initialDelaySeconds: 10         # Wait 10s before first check (JVM boot)
      periodSeconds: 5                # Check every 5 seconds
      timeoutSeconds: 3               # Fail if no response in 3s
      failureThreshold: 20            # Allow 20 failures before killing pod
      # Max startup time = 10 + (20 * 5) = 110 seconds
      # WHY 110s: Kafka Streams needs time to:
      #   1. Connect to brokers
      #   2. Join consumer group
      #   3. Complete rebalance
      #   4. Initialize topology

    # ┌─────────────────────────────────────────────────────────────┐
    # │ LIVENESS PROBE                                             │
    # │ Purpose: Detect zombie pods (JVM alive but not working).   │
    # │ If this fails 3 times → kubelet KILLS and RESTARTS pod.    │
    # │ Only activated AFTER startup probe succeeds.               │
    # └─────────────────────────────────────────────────────────────┘
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: management              # Port 9090
      initialDelaySeconds: 60         # Extra buffer after startup probe passes
      periodSeconds: 10               # Check every 10 seconds
      timeoutSeconds: 5               # Fail if no response in 5s
      failureThreshold: 3             # Kill after 3 consecutive failures
      # Time to restart = 3 * 10 = 30 seconds of consecutive failures
      # WHY 30s: Fast enough to catch zombies, slow enough to survive GC pauses.

    # ┌─────────────────────────────────────────────────────────────┐
    # │ READINESS PROBE                                            │
    # │ Purpose: Control whether pod receives K8s Service traffic. │
    # │ If this fails → pod removed from Service endpoints (NOT    │
    # │ restarted). Traffic goes to other pods.                    │
    # └─────────────────────────────────────────────────────────────┘
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: management              # Port 9090
      initialDelaySeconds: 30         # Wait for initial setup
      periodSeconds: 5                # Check every 5 seconds (more frequent than liveness)
      timeoutSeconds: 3               # Fail if no response in 3s
      failureThreshold: 2             # Remove from traffic after 2 consecutive failures
      # WHY faster than liveness: Readiness affects traffic routing,
      # not pod lifecycle. Quick reaction prevents sending requests to unhealthy pods.
```

---

## What Makes Liveness Fail?

| Event | Liveness Result | Action |
|-------|----------------|--------|
| App running normally | UP (200) | Nothing |
| Kafka broker down | UP (200) | Nothing — broker down ≠ pod broken |
| StreamFatalExceptionHandler fires SHUTDOWN_CLIENT | DOWN (503) | Kill + restart pod |
| JVM deadlock / OOM | No response (timeout) | Kill + restart pod |
| GC pause > 5s | Timeout (1 failure) | Wait — needs 3 failures to kill |

The key insight: **liveness only fails for application-level breakage** (SHUTDOWN_CLIENT publishes `LivenessState.BROKEN`). External dependencies like Kafka broker being down do NOT trigger restarts.

---

## What Makes Readiness Fail?

| Event | Readiness Result | Action |
|-------|-----------------|--------|
| App running, Kafka connected | UP (200) | Pod receives Service traffic |
| App starting up (not ready yet) | DOWN (503) | No traffic until ready |
| Kafka broker unreachable | DOWN (503) | Traffic rerouted to healthy pods |

**Important:** For Kafka Streams, readiness primarily gates REST API traffic. Kafka partition assignment is managed by the consumer group protocol, NOT by K8s readiness.

---

## How LivenessState.BROKEN Works

When `StreamFatalExceptionHandler` detects a fatal error:

```java
// StreamFatalExceptionHandler.java
AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
```

This publishes a Spring Boot availability event:
1. Spring Boot's internal `LivenessStateHealthIndicator` picks it up
2. `/actuator/health/liveness` starts returning HTTP 503
3. Kubelet sees 3 × 503 → kills pod → K8s restarts it

**Without this explicit publish:** SHUTDOWN_CLIENT stops streams but JVM stays alive. The liveness endpoint might still return 200 → zombie pod. The explicit publish eliminates this risk.

---

## Prometheus Metrics Scraping

```yaml
# k8s-manifest.yaml — ServiceMonitor (for Prometheus Operator)
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: payments-stream
  namespace: financial-streams
  labels:
    app: payments-stream              # Must match Prometheus serviceMonitorSelector
spec:
  selector:
    matchLabels:
      app: payments-stream            # Targets pods with this label
  endpoints:
    - port: management                # Scrape from port 9090
      path: /actuator/prometheus      # Prometheus-format metrics
      interval: 30s                   # Scrape every 30 seconds
      scrapeTimeout: 10s              # Timeout per scrape
```

**Metrics automatically available at `/actuator/prometheus`:**
- JVM: memory, GC, threads, classloading
- Kafka: consumer lag, poll latency, commit latency, producer acks
- Spring: request count, response times, active connections
- Custom: any gauge/counter registered via `MeterRegistry`

---

## K8s Service — Dual Port Exposure

```yaml
apiVersion: v1
kind: Service
metadata:
  name: payments-stream
  namespace: financial-streams
spec:
  type: ClusterIP
  ports:
    # Business API port — behind mTLS/SPIFFE
    - name: http
      port: 8080
      targetPort: http
      protocol: TCP
    # Management port — probes + Prometheus (no mTLS)
    - name: management
      port: 9090
      targetPort: management
      protocol: TCP
  selector:
    app: payments-stream
```

---

## Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Separate management port | 9090 | Kubelet/Prometheus can't do mTLS |
| Startup probe | 110s max | Kafka Streams needs time for rebalance |
| Liveness failure threshold | 3 × 10s = 30s | Tolerates GC pauses but catches zombies |
| Readiness failure threshold | 2 × 5s = 10s | Fast traffic rerouting |
| `show-details: always` | All details visible | Management port is internal, not exposed |
| Kafka health → overall only | Not in liveness | Broker down ≠ restart pod |
| `LivenessState.BROKEN` | Explicit publish | Prevents zombie pods after SHUTDOWN_CLIENT |

---

## Files to Create/Modify

```
src/main/resources/application.yml   # management.* section (shown above)
k8s-manifest.yaml                    # Probes + ServiceMonitor (shown above)
```

**Java code:** `StreamFatalExceptionHandler` already publishes `LivenessState.BROKEN` — no separate health indicator needed for stage 1 (no circuit breaker).

---

## Verification Checklist

- [ ] `curl http://localhost:9090/actuator/health` returns 200 with full details
- [ ] `curl http://localhost:9090/actuator/health/liveness` returns `{"status":"UP"}`
- [ ] `curl http://localhost:9090/actuator/health/readiness` returns `{"status":"UP"}`
- [ ] `curl http://localhost:9090/actuator/prometheus` returns Prometheus-format metrics
- [ ] Business API on 8080: `curl http://localhost:8080/api/fs/hello` returns greeting
- [ ] Port 9090 does NOT serve business APIs (404 on `/api/fs/hello`)
- [ ] Port 8080 does NOT serve actuator (404 on `/actuator/health`)
- [ ] Pod restarts after `StreamFatalExceptionHandler` fires SHUTDOWN_CLIENT (check `kubectl get pods -w`)
- [ ] Pod does NOT restart when Kafka broker is temporarily down
