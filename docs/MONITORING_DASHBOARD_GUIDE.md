# Monitoring Dashboard Guide

## Overview

The monitoring dashboard provides a **single-page real-time view** of your application's runtime behaviour, including:

- **Pod scaling** — current/desired/ready replicas, pod list with status and age
- **Kafka consumer lag** — per-partition lag breakdown with severity indicators
- **KEDA autoscaling** — HPA current/desired replicas, min/max, conditions, last scale time
- **Circuit breaker health** — state (CLOSED / HALF_OPEN / OPEN), open count, next restart delay
- **Kafka Streams health** — stream state, thread count, active/standby tasks
- **JVM resources** — heap/non-heap usage, CPU, uptime

The dashboard HTML (`/api/fs/monitoring.html`) is served statically by Spring Boot. It connects to the backend via REST polling and Server-Sent Events (SSE) for real-time push updates.

---

## Architecture

```
Browser (monitoring.html)
    │
    ├── SSE  GET /api/fs/monitoring/sse        (real-time push every 5s)
    └── REST GET /api/fs/monitoring/dashboard  (full snapshot on demand)
                        │
            MonitoringDashboardController
                        │
            MonitoringDashboardService  ◄─── AtomicReference cache
              ├── RuntimeDiscoveryService     (existing — Kafka Streams, JVM, pod, CB config)
              ├── KubernetesMonitoringService (NEW — pods, deployment, HPA via K8s API)
              ├── KafkaLagMonitoringService   (NEW — per-partition lag via AdminClient)
              └── BusinessOutcomeCircuitBreaker (existing — CB state, open count)
                        │
            Management port (9090)
            GET /actuator/monitoring           (same data via Actuator)
```

**Data flows:**
1. `KubernetesMonitoringService` uses the Kubernetes Java Client with in-cluster config (ServiceAccount token) to query pods, deployment, and HPA from the Kubernetes API.
2. `KafkaLagMonitoringService` creates a Kafka AdminClient, fetches committed offsets and end offsets, computes per-partition lag.
3. `MonitoringDashboardService` aggregates all sources; any failed section is replaced with an error indicator — the rest of the dashboard continues working (graceful degradation).
4. `MonitoringDashboardController` exposes REST endpoints and an SSE stream.
5. The browser connects via SSE and falls back to polling if SSE is unavailable.

---

## Prerequisites

### 1. RBAC (Kubernetes only)

Before enabling Kubernetes API calls, deploy the RBAC manifest:

```bash
kubectl apply -f k8s-rbac-monitoring.yaml -n financial-streams
```

Verify access:

```bash
kubectl auth can-i list pods \
  --as=system:serviceaccount:financial-streams:payments-stream \
  -n financial-streams
```

Expected output: `yes`

### 2. Maven dependency

The Kubernetes Java Client is added to `pom.xml`:

```xml
<dependency>
    <groupId>io.kubernetes</groupId>
    <artifactId>client-java-spring-integration</artifactId>
    <version>21.0.2</version>
</dependency>
```

---

## Quick Start

### Local Development

```bash
mvn spring-boot:run
# Dashboard UI:  http://localhost:8080/api/fs/monitoring.html
# Dashboard API: http://localhost:8080/api/fs/monitoring/dashboard
# Actuator:      http://localhost:9090/actuator/monitoring
```

Kubernetes API calls are **disabled** by default (`app.monitoring.k8s-enabled=false`), so you will see "K8s disabled" placeholders in the Pods and KEDA panels.

### AKS / Kubernetes

1. Deploy RBAC: `kubectl apply -f k8s-rbac-monitoring.yaml`
2. Deploy the app: `kubectl apply -f k8s-manifest.yaml`
3. The `K8S_ENABLED=true` env var is already set in `k8s-manifest.yaml`.
4. Access the dashboard via port-forward:
   ```bash
   kubectl port-forward deployment/payments-stream 8080:8080 -n financial-streams
   # Open: http://localhost:8080/api/fs/monitoring.html
   ```

---

## API Reference

All endpoints are on the **business port 8080** under context-path `/api/fs`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/monitoring/dashboard` | Full dashboard JSON — all sections |
| `GET` | `/monitoring/pods` | Pod list and count from Kubernetes API |
| `GET` | `/monitoring/kafka-lag` | Per-partition Kafka consumer lag |
| `GET` | `/monitoring/keda` | KEDA/HPA scaling status |
| `GET` | `/monitoring/circuit-breaker` | Circuit breaker state |
| `GET` | `/monitoring/streams` | Kafka Streams health |
| `GET` | `/monitoring/sse` | SSE stream — push updates every 5s |
| `GET` (port 9090) | `/actuator/monitoring` | Same dashboard data on management port |

### Example response: `GET /monitoring/dashboard`

```json
{
  "timestamp": "2026-03-22T10:30:00Z",
  "refreshIntervalMs": 5000,
  "meta": {
    "application": "kafka-streams-cb",
    "profiles": ["prod"],
    "springBootVersion": "3.5.9"
  },
  "pod": {
    "podName": "payments-stream-7b4f9-xk2m9",
    "namespace": "financial-streams",
    "nodeName": "aks-pool1-12345",
    "podIp": "10.244.1.5"
  },
  "jvm": {
    "heapUsedMB": 320,
    "heapMaxMB": 1024,
    "uptimeSeconds": 3600,
    "availableProcessors": 4
  },
  "kafkaStreams": {
    "state": "RUNNING",
    "threads": [{"threadName": "...", "activeTasks": [...],"standbyTasks": []}]
  },
  "circuitBreaker": {
    "state": "CLOSED",
    "openCount": 0,
    "nextRestartDelay": "PT1M"
  },
  "kafkaLag": {
    "thresholds": { "warning": 100, "critical": 500 },
    "consumerGroups": [
      {
        "consumerGroup": "payments-stream-v1",
        "totalLag": 0,
        "avgLagPerPartition": 0,
        "maxLag": 0,
        "partitionsWithLag": 0,
        "totalPartitions": 12,
        "severity": "OK",
        "partitions": [
          { "topic": "payments.input", "partition": 0, "committedOffset": 1000, "endOffset": 1000, "lag": 0, "severity": "OK" }
        ]
      }
    ]
  },
  "scaling": {
    "pods": {
      "count": 3,
      "pods": [
        { "name": "payments-stream-abc", "phase": "Running", "ready": true, "age": "2h", "nodeName": "aks-pool1-12345", "podIp": "10.244.1.5" }
      ]
    },
    "deployment": { "desiredReplicas": 3, "currentReplicas": 3, "readyReplicas": 3 },
    "hpa": { "minReplicas": 2, "maxReplicas": 48, "currentReplicas": 3, "desiredReplicas": 3 }
  }
}
```

---

## Configuration Reference

All properties are under the `app.monitoring` prefix.

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Enable/disable the entire monitoring dashboard |
| `refresh-interval-ms` | `5000` | How often the dashboard data is refreshed (ms) |
| `sse-heartbeat-ms` | `5000` | How often SSE pushes to connected browsers (ms) |
| `k8s-api-timeout-ms` | `3000` | Kubernetes API call timeout (ms) |
| `namespace` | `${POD_NAMESPACE:financial-streams}` | Kubernetes namespace to query |
| `deployment-name` | `payments-stream` | Deployment name to watch |
| `pod-label-selector` | `app=payments-stream` | Label selector to list pods |
| `kafka-consumer-groups` | `[payments-stream-v1, payments-exception-v1]` | Consumer groups to monitor |
| `lag-threshold-warning` | `100` | Lag below this is green |
| `lag-threshold-critical` | `500` | Lag at or above this is red |
| `k8s-enabled` | `${K8S_ENABLED:false}` | Enable real Kubernetes API calls (false for local dev) |

---

## Environment Setup

### Local Dev (default)

```properties
app.monitoring.k8s-enabled=false
```

Pods and KEDA panels show a placeholder. All other panels (lag, circuit breaker, Kafka Streams, JVM) still work as long as Kafka is available.

### Dev / Test on Kubernetes

Set the following env vars in your deployment:
```yaml
- name: K8S_ENABLED
  value: "true"
- name: SPRING_PROFILES_ACTIVE
  value: "dev"
```

Deploy RBAC first: `kubectl apply -f k8s-rbac-monitoring.yaml`.

### Production (AKS)

The `k8s-manifest.yaml` already sets `K8S_ENABLED=true` and `SPRING_PROFILES_ACTIVE=prod`. The `application-prod.yml` sets `k8s-enabled: true` and disables Swagger.

---

## Adoption Guide

To adopt this monitoring dashboard in **any Spring Boot Kafka Streams project**, follow these steps:

### Step 1 — Copy the monitoring package

Copy the following files to your project's monitoring package:

```
src/main/java/com/example/<yourapp>/monitoring/
  MonitoringProperties.java
  KubernetesMonitoringService.java
  KafkaLagMonitoringService.java
  MonitoringDashboardService.java
  MonitoringDashboardController.java
  MonitoringActuatorEndpoint.java
```

### Step 2 — Update the dashboard service

In `MonitoringDashboardService`, inject your own `RuntimeDiscoveryService` (or equivalent) and `BusinessOutcomeCircuitBreaker` (or your own circuit breaker abstraction).

### Step 3 — Update pom.xml

```xml
<dependency>
    <groupId>io.kubernetes</groupId>
    <artifactId>client-java-spring-integration</artifactId>
    <version>21.0.2</version>
</dependency>
```

### Step 4 — Copy application-dev.yml / application-prod.yml

Update with your app's namespace, deployment name, label selector, and consumer groups.

### Step 5 — Copy monitoring.html

No changes needed unless you want to rebrand. Update the title and `<app-title>` in the HTML.

### Step 6 — Customize configuration

In `application.properties` (or yml), set:

```properties
app.monitoring.deployment-name=<your-deployment-name>
app.monitoring.pod-label-selector=app=<your-label>
app.monitoring.kafka-consumer-groups[0]=<your-consumer-group>
app.monitoring.namespace=${POD_NAMESPACE:<your-default-namespace>}
```

### Step 7 — Deploy RBAC

Update `k8s-rbac-monitoring.yaml` with your namespace and ServiceAccount name, then deploy:

```bash
kubectl apply -f k8s-rbac-monitoring.yaml
```

### Step 8 — Enable Kubernetes API

Set `K8S_ENABLED=true` in your Kubernetes Deployment environment variables.

---

## Security Considerations

- **RBAC scope**: The Role grants only `get`, `list`, and `watch` on pods, deployments, HPAs, and ScaledObjects. No write permissions. No access to Secrets.
- **Management port isolation**: The `/actuator/monitoring` endpoint is on port 9090 (management port), which is cluster-internal and not exposed via Ingress/SPIFFE.
- **No credentials exposed**: The dashboard aggregates only structural data (replica counts, states, lag). No Kafka credentials, passwords, or API keys are ever included in the response. The `RuntimeDiscoveryService.Sanitiser` ensures SASL configs are redacted.
- **Read-only filesystem**: The monitoring dashboard never writes to disk. All data is in-memory.
- **SSE cleanup**: Disconnected SSE clients are removed from the emitter list immediately — no resource leak.

---

## Troubleshooting

### Dashboard shows "K8s API disabled" in Pods panel

**Cause**: `app.monitoring.k8s-enabled=false` (default for local dev).

**Fix**: Deploy RBAC manifest, then set `K8S_ENABLED=true` in your deployment.

### Dashboard shows "K8s API error 403" in Pods panel

**Cause**: The ServiceAccount does not have the required RBAC permissions.

**Fix**: `kubectl apply -f k8s-rbac-monitoring.yaml -n <your-namespace>`

### Kafka Lag shows "AdminClient unavailable"

**Cause**: Kafka broker is unreachable (wrong bootstrap servers, or local dev without Kafka running).

**Fix**: Start the Kafka broker (`docker-compose up -d`) or check `spring.kafka.bootstrap-servers`.

### SSE pill shows "Polling" (red dot) instead of "Live" (green dot)

**Cause**: SSE connection failed or browser blocked it.

**Fix**: This is normal in some proxy/load balancer setups. The dashboard automatically falls back to REST polling every 5 seconds.

### Circuit breaker panel shows "NOT_INITIALIZED"

**Cause**: The `BusinessOutcomeCircuitBreaker` Spring bean was not found (e.g., Kafka Streams not configured).

**Fix**: This is expected in local dev without Kafka Streams wired up. In production, the bean is always present.
