# Observability Step-by-Step Guide

> **Pipeline stages to measure:**
> 1. Kafka receive / deserialize
> 2. CSFLE decrypt / serde cost
> 3. Reference data API call
> 4. Business logic / enrichment
> 5. DB save / update
> 6. Kafka produce / serialize / encrypt
> 7. End-to-end total

---

## How It All Connects

```
Your Spring Boot App
  │
  ├── Micrometer Timers (code) ──────┬──→ /actuator/metrics     (curl from laptop)
  │                                  ├──→ /actuator/prometheus   (Prometheus scrapes this)
  │                                  └──→ App Insights           (auto-export if agent present)
  │
  ├── Prometheus (cluster) ──────────────→ Grafana dashboards
  │
  ├── App Insights (Azure) ─────────────→ Azure Portal charts + KQL
  │
  └── JMX (pod) ────────────────────────→ VisualVM (your laptop)
```

> **One set of Micrometer timers feeds all 3 destinations.** Section 2 is the key code change.

---

## Section 0: Pre-Check — What You Already Have (5 min)

### 0a. Check Azure Monitor / Container Insights

1. **Azure Portal → your AKS cluster → Monitoring → Insights**
2. If you see CPU/memory/pod charts → Container Insights is enabled
3. Your pods are already shipping logs + metrics to Azure Log Analytics

### 0b. Check if `/actuator/prometheus` Exposes Metrics

```powershell
# Find your pod
kubectl get pods -n <namespace> -l app=<your-app-label>

# Port-forward management port
kubectl port-forward pod/<pod-name> 9090:9090 -n <namespace>

# In another terminal — check for metrics
Invoke-WebRequest http://localhost:9090/actuator/prometheus | Select-Object -ExpandProperty Content | Select-String "jvm_memory"
```

| Result | Meaning |
|---|---|
| ✅ Response with `jvm_memory_used_bytes` | Actuator + Prometheus registry working |
| ❌ 404 | `micrometer-registry-prometheus` missing from pom.xml (see Section 4c) |
| ✅ `circuit_breaker_*` metrics present | Your 3 existing gauges are registered |

### 0c. Check if Prometheus Is Scraping Your Pods

```powershell
# Is Prometheus running in cluster?
kubectl get pods --all-namespaces | Select-String "prometheus"

# Does your pod have scrape annotations?
kubectl get pod <pod-name> -n <namespace> -o yaml | Select-String "prometheus"
```

Look for:
```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "9090"
  prometheus.io/path: "/actuator/prometheus"
```

| Result | Meaning |
|---|---|
| ✅ Annotations exist | Prometheus already scraping → new timers auto-appear |
| ❌ No annotations | Add to deployment manifest + redeploy (see Section 4b) |

### 0d. Check Grafana Availability

```powershell
# Check if Grafana is running
kubectl get pods --all-namespaces | Select-String "grafana"
kubectl get svc --all-namespaces | Select-String "grafana"
```

Or ask your platform team for the Grafana URL. If your infra Grafana already shows pod CPU/memory, it's connected to Prometheus and ready for custom metrics.

### 0e. Check Azure Log Analytics Data

**Azure Portal → Log Analytics workspace → Logs:**

```kusto
// Are your app logs flowing?
ContainerLogV2
| where PodName contains "<your-app-name>"
| take 10
```

```kusto
// What perf counters exist?
Perf
| where ObjectName == "K8SContainer"
| where InstanceName contains "<your-app-name>"
| distinct CounterName
```

---

## Section 1: VisualVM — CPU Profile via JMX (No Code Change)

### What It Gives You
- Method-level hot spot analysis — find exactly which methods are slow
- Real-time CPU/memory monitoring
- No code changes, no redeploy

### 1a. Find Your JMX Port

```powershell
kubectl get pod <pod-name> -n <namespace> -o yaml | Select-String "jmxremote"
```

Or check Dockerfile / deployment manifest for:
```
-Dcom.sun.management.jmxremote.port=9010
```
Common ports: `9010`, `5555`, `1099`

### 1b. Port-Forward JMX

```powershell
# Keep this terminal open
kubectl port-forward pod/<pod-name> 9010:9010 -n <namespace>
```

### 1c. Connect VisualVM

1. Open VisualVM
2. **File → Add JMX Connection**
3. Connection: `localhost:9010`
4. **Uncheck** "Use SSL"
5. Click OK → pod's JVM appears in left panel

### 1d. If Connection Fails

Check for this JVM arg on the pod:
```
-Djava.rmi.server.hostname=127.0.0.1
```

```powershell
kubectl get pod <pod-name> -n <namespace> -o yaml | Select-String "rmi.server.hostname"
```

If missing → add to deployment JVM args and redeploy. Without it, JMX returns pod's internal IP which your laptop can't reach.

### 1e. CPU Profiling Workflow

| Tab | What It Shows | When to Use |
|---|---|---|
| **Monitor** | CPU %, heap, threads | Quick health check |
| **Sampler → CPU** | Hot methods by self-time | **Start here — primary tool** |
| **Sampler → Memory** | Object allocation rates | If you suspect GC |
| **Profiler → CPU** | Instrumented call counts | Only non-prod (high overhead) |

**Steps:**
1. Go to **Sampler** tab → click **CPU**
2. Send load to Kafka topic (JMeter or kafka-console-producer)
3. Run for **2-3 minutes** under load
4. Click **Stop**
5. **Hot Spots** view → sort by **Self Time**

### 1f. Map Hot Methods to Pipeline Stages

| Method Pattern | Pipeline Stage |
|---|---|
| `JsonDeserializer.deserialize` / `ObjectMapper.readValue` | 1. Deserialize |
| `CsfleCryptoService.*` / `normalizeAfterSerde` | 2. CSFLE decrypt |
| `WebClient` / `RestTemplate` / `HttpClient` calls | 3. Ref data API |
| `DefaultBusinessProcessorService.process` | 4. Business logic |
| `logSoftFailure` / JDBC / Mongo methods | 5. DB save |
| `KafkaTemplate.executeInTransaction` / `KafkaProducer.send` | 6. Kafka produce |
| `JsonSerializer.serialize` | 6. Serialize cost |

### 1g. Save and Share

File → **Save Snapshot As** → `.nps` file → share with team

### 1h. Sampler vs Profiler

| | Sampler | Profiler |
|---|---|---|
| **Overhead** | ~2-5% (safe near-prod) | ~20-50% (NOT safe for prod) |
| **Detail** | Method self-time | Self-time + call counts |
| **Use** | **Always start here** | Only non-prod pods |

**Tip:** Filter by `com.example.financialstream` to focus on your code.

---

## Section 2: Micrometer — Add Pipeline Stage Timers

### What It Gives You
- Per-stage latency (avg, p50, p95, p99)
- Data feeds to actuator, Prometheus, AND App Insights simultaneously
- This is the single most important code change

### 2a. Add Prometheus Registry to pom.xml

Your pom.xml has `spring-boot-starter-actuator` but **not** the Prometheus registry.

```xml
<!-- Add to pom.xml <dependencies> -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 2b. Enable Percentile Histograms

Add to `application.properties`:

```properties
# Enable p50/p95/p99 percentiles for pipeline stage timers
management.metrics.distribution.percentiles.pipeline.stage.duration=0.5,0.95,0.99
management.metrics.distribution.percentiles-histogram.pipeline.stage.duration=true
```

### 2c. Create Test Endpoint for Repeated Calls

Create: `src/main/java/com/example/financialstream/controller/PipelineTestController.java`

```java
package com.example.financialstream.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Temporary test endpoint to generate pipeline stage metrics.
 * Remove before production release.
 */
@RestController
@RequestMapping("/api/test")
public class PipelineTestController {

    private final Timer deserializeTimer;
    private final Timer csfleTimer;
    private final Timer businessLogicTimer;
    private final Timer dbSaveTimer;
    private final Timer kafkaProduceTimer;
    private final Timer e2eTimer;

    public PipelineTestController(MeterRegistry registry) {
        this.deserializeTimer   = Timer.builder("pipeline.stage.duration")
                .tag("stage", "deserialize").register(registry);
        this.csfleTimer         = Timer.builder("pipeline.stage.duration")
                .tag("stage", "csfle_decrypt").register(registry);
        this.businessLogicTimer = Timer.builder("pipeline.stage.duration")
                .tag("stage", "business_logic").register(registry);
        this.dbSaveTimer        = Timer.builder("pipeline.stage.duration")
                .tag("stage", "db_save").register(registry);
        this.kafkaProduceTimer  = Timer.builder("pipeline.stage.duration")
                .tag("stage", "kafka_produce").register(registry);
        this.e2eTimer           = Timer.builder("pipeline.stage.duration")
                .tag("stage", "e2e_total").register(registry);
    }

    @PostMapping("/simulate-pipeline")
    public Map<String, Object> simulatePipeline(@RequestBody Map<String, String> input) {
        Timer.Sample e2eSample = Timer.start();

        // Stage 1: Simulate deserialize
        deserializeTimer.record(() -> simulateWork(1, 5));

        // Stage 2: Simulate CSFLE decrypt
        csfleTimer.record(() -> simulateWork(2, 10));

        // Stage 3+4: Simulate business logic
        businessLogicTimer.record(() -> simulateWork(1, 3));

        // Stage 5: Simulate DB save
        dbSaveTimer.record(() -> simulateWork(5, 20));

        // Stage 6: Simulate Kafka produce
        kafkaProduceTimer.record(() -> simulateWork(3, 15));

        e2eSample.stop(e2eTimer);

        return Map.of(
            "status", "processed",
            "input", input.getOrDefault("eventId", "unknown"),
            "e2e_ms", e2eTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        );
    }

    private void simulateWork(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 2d. Send Repeated Calls with Different Inputs

```powershell
# Port-forward app port
kubectl port-forward pod/<pod-name> 8080:8080 -n <namespace>

# Send 50 calls with different inputs
1..50 | ForEach-Object {
    $body = @{ eventId = "EVT-$_"; cid = "CID-$_"; payload = "data-$_" } | ConvertTo-Json
    Invoke-RestMethod -Uri "http://localhost:8080/api/fs/api/test/simulate-pipeline" `
        -Method POST -Body $body -ContentType "application/json"
}
```

### 2e. View Metrics via Actuator

```powershell
# Port-forward management port
kubectl port-forward pod/<pod-name> 9090:9090 -n <namespace>

# All pipeline timers
Invoke-RestMethod http://localhost:9090/actuator/metrics/pipeline.stage.duration

# Specific stage
Invoke-RestMethod "http://localhost:9090/actuator/metrics/pipeline.stage.duration?tag=stage:kafka_produce"

# Prometheus format
Invoke-WebRequest http://localhost:9090/actuator/prometheus | Select-Object -ExpandProperty Content | Select-String "pipeline_stage"
```

**Output:**
```
pipeline_stage_duration_seconds_count{stage="deserialize"} 50.0
pipeline_stage_duration_seconds_sum{stage="deserialize"} 0.142
pipeline_stage_duration_seconds_max{stage="deserialize"} 0.005
pipeline_stage_duration_seconds{stage="kafka_produce",quantile="0.5"} 0.009
pipeline_stage_duration_seconds{stage="kafka_produce",quantile="0.95"} 0.014
pipeline_stage_duration_seconds{stage="kafka_produce",quantile="0.99"} 0.015
```

### 2f. Quick Math from Actuator

```
avg latency = sum / count
e.g. 0.142 / 50 = 2.84ms avg deserialize
```

With percentiles enabled (Step 2b), p50/p95/p99 appear directly in the output.

---

## Section 3: Azure Application Insights

### What It Gives You
- Visual charts in Azure Portal
- KQL queries for histogram/percentile analysis
- Auto-captures Micrometer metrics — no extra code if agent is present

### 3a. Verify App Insights Is Working

**Check 1 — Dependency in production pom.xml:**
```xml
<dependency>
    <groupId>com.microsoft.azure</groupId>
    <artifactId>applicationinsights-runtime-attach</artifactId>
    <version>3.6.2</version>
</dependency>
```

**Check 2 — Connection string set in AKS:**
```powershell
kubectl get pod <pod-name> -n <namespace> -o yaml | Select-String "APPLICATIONINSIGHTS"
```
Should show `APPLICATIONINSIGHTS_CONNECTION_STRING` env var.

**Check 3 — Data flowing:**
Azure Portal → Application Insights → Overview → see requests/dependencies charts? → Working.

### 3b. Find Your Custom Metrics

1. **Azure Portal → Application Insights → Metrics**
2. Click **+ Add metric**
3. Metric Namespace → **Custom** or **azure.applicationinsights**
4. Browse dropdown → your custom metric names should appear
5. If you see them → they're flowing. Just need to build charts.

### 3c. Build a Pipeline Stage Chart

1. **Azure Portal → Application Insights → Metrics**
2. **+ Add metric** → Namespace: **Custom**
3. Metric → select your metric name (e.g., `pipeline.stage.duration`)
4. Aggregation → **Avg**
5. Click **Apply splitting** → split by **stage** tag
6. Time range → **Last 1 hour**
7. Click **Pin to dashboard** → saves to your Azure Dashboard

### 3d. KQL Queries for Histogram Analysis

Go to **Application Insights → Logs** and run:

**Percentile summary per stage:**
```kusto
customMetrics
| where name startswith "pipeline.stage"
| extend stage = tostring(customDimensions["stage"])
| where timestamp > ago(1h)
| summarize 
    count = count(),
    avg_ms = avg(value),
    p50 = percentile(value, 50),
    p95 = percentile(value, 95),
    p99 = percentile(value, 99),
    max_ms = max(value)
    by stage
| order by avg_ms desc
```

**Time-series chart (latency over time, 5-min buckets):**
```kusto
customMetrics
| where name startswith "pipeline.stage"
| extend stage = tostring(customDimensions["stage"])
| where timestamp > ago(1h)
| summarize avg_ms = avg(value) by stage, bin(timestamp, 5m)
| render timechart
```

**Histogram — how many calls in each latency bucket:**
```kusto
customMetrics
| where name startswith "pipeline.stage"
| extend stage = tostring(customDimensions["stage"])
| extend latency_bucket = case(
    value < 5, "0-5ms",
    value < 10, "5-10ms",
    value < 20, "10-20ms",
    value < 50, "20-50ms",
    value < 100, "50-100ms",
    ">100ms")
| summarize count() by stage, latency_bucket
| order by stage asc, latency_bucket asc
```

**Slow calls only (over 50ms):**
```kusto
customMetrics
| where name startswith "pipeline.stage"
| extend stage = tostring(customDimensions["stage"])
| where value > 50
| project timestamp, stage, value
| order by value desc
| take 50
```

### 3e. Key Insight — Auto-Export

If App Insights Java agent is attached, **all Micrometer timers are automatically exported** to `customMetrics`. The timers from Section 2 will appear here with zero extra code. One set of timers → three destinations (actuator, Prometheus, App Insights).

---

## Section 4: Prometheus

### What It Gives You
- Time-series metrics database in your cluster
- Source data for Grafana dashboards (Section 5)
- PromQL for alerting rules

### 4a. Check if Prometheus Exists

```powershell
kubectl get pods --all-namespaces | Select-String "prometheus"
kubectl get svc --all-namespaces | Select-String "prometheus"
```

Look for `prometheus-server`, `kube-prometheus-stack`, or `prometheus-operator`.

### 4b. Ensure Your Pod Has Scrape Annotations

```powershell
kubectl get pod <pod-name> -n <namespace> -o yaml | Select-String "prometheus"
```

Required annotations in your deployment manifest:
```yaml
spec:
  template:
    metadata:
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9090"
        prometheus.io/path: "/actuator/prometheus"
```

If missing → add to deployment YAML → `kubectl apply` → redeploy.

### 4c. Add Prometheus Registry (if missing from pom.xml)

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Without this, `/actuator/prometheus` returns 404.

### 4d. Verify Endpoint

```powershell
kubectl port-forward pod/<pod-name> 9090:9090 -n <namespace>

# Should return all metrics in Prometheus text format
Invoke-WebRequest http://localhost:9090/actuator/prometheus | Select-Object -ExpandProperty Content | Select-String "pipeline_stage"
```

### 4e. Query in Prometheus UI

```powershell
# Port-forward Prometheus
kubectl port-forward svc/prometheus-server 9091:80 -n monitoring
```

Open `http://localhost:9091` and run:

```promql
# Average latency per stage (last 5 min)
rate(pipeline_stage_duration_seconds_sum[5m]) / rate(pipeline_stage_duration_seconds_count[5m])

# P95 latency per stage
histogram_quantile(0.95, rate(pipeline_stage_duration_seconds_bucket[5m]))

# Throughput — records/sec per stage
rate(pipeline_stage_duration_seconds_count[5m])
```

---

## Section 5: Grafana — Dashboards for Pipeline Metrics

### What It Gives You
- Visual dashboards with drill-down
- Alerting on latency thresholds
- Shareable with team — no kubectl needed

### 5a. Check Grafana Access

```powershell
# Find Grafana in cluster
kubectl get svc --all-namespaces | Select-String "grafana"
```

Or ask platform team for the Grafana URL. If you already see pod CPU/memory in Grafana → it's connected to Prometheus → ready for your custom metrics.

### 5b. Verify Your Metrics Exist in Grafana's Prometheus

1. Open Grafana → click **Explore** (compass icon on left sidebar)
2. Data source dropdown → select **Prometheus**
3. In the query box, type: `pipeline_stage_duration_seconds_count`
4. Click **Run query**

| Result | Meaning |
|---|---|
| ✅ See data | Prometheus is scraping your app → build dashboards |
| ❌ No results | Check: pod has scrape annotations (4b), pom.xml has registry (4c), `/actuator/prometheus` returns data (4d) |

### 5c. Create a New Dashboard

1. Grafana → left sidebar → **+** → **New Dashboard**
2. Click **Add visualization**
3. Data source → **Prometheus**

### 5d. Panel 1 — Average Latency per Stage (Bar Gauge)

**PromQL:**
```promql
rate(pipeline_stage_duration_seconds_sum{stage!=""}[5m]) / rate(pipeline_stage_duration_seconds_count{stage!=""}[5m]) * 1000
```

**Panel settings:**
- Visualization type: **Bar Gauge** (or **Stat**)
- Legend: `{{stage}}`
- Unit: **milliseconds (ms)**
- Title: `Avg Latency per Pipeline Stage`

**What it shows:** One bar per stage — instantly see which stage is slowest.

### 5e. Panel 2 — P95 Latency per Stage (Time Series)

**PromQL:**
```promql
histogram_quantile(0.95, rate(pipeline_stage_duration_seconds_bucket{stage!=""}[5m])) * 1000
```

**Panel settings:**
- Visualization type: **Time series**
- Legend: `{{stage}}`
- Unit: **milliseconds (ms)**
- Title: `P95 Latency per Pipeline Stage`

**What it shows:** P95 latency over time — spot degradation trends.

### 5f. Panel 3 — Throughput per Stage (Time Series)

**PromQL:**
```promql
rate(pipeline_stage_duration_seconds_count{stage!=""}[5m])
```

**Panel settings:**
- Visualization type: **Time series**
- Legend: `{{stage}}`
- Unit: **ops/sec**
- Title: `Throughput per Pipeline Stage`

**What it shows:** Records processed per second — confirms load is flowing.

### 5g. Panel 4 — E2E Total Latency Heatmap

**PromQL:**
```promql
rate(pipeline_stage_duration_seconds_bucket{stage="e2e_total"}[5m])
```

**Panel settings:**
- Visualization type: **Heatmap**
- Unit: **seconds**
- Title: `E2E Latency Distribution`

**What it shows:** Distribution of end-to-end latency — see where most requests land.

### 5h. Panel 5 — Circuit Breaker State (Stat)

**PromQL:**
```promql
circuit_breaker_current_state{breaker="payments-business-soft-failure"}
```

**Panel settings:**
- Visualization type: **Stat**
- Value mappings:
  - `0` → `CLOSED` (green)
  - `1` → `OPEN` (red)  
  - `2` → `HALF_OPEN` (yellow)
- Title: `Circuit Breaker State`

### 5i. Suggested Dashboard Layout

```
Row 1: Overview
┌──────────────────────┬──────────────────────┬──────────────┐
│ Avg Latency (Bar)    │ Throughput (Series)   │ CB State     │
│ Panel 1              │ Panel 3               │ Panel 5      │
└──────────────────────┴──────────────────────┴──────────────┘

Row 2: Deep Dive
┌──────────────────────────────────┬─────────────────────────┐
│ P95 Latency (Time Series)       │ E2E Heatmap             │
│ Panel 2                         │ Panel 4                 │
└──────────────────────────────────┴─────────────────────────┘
```

### 5j. Save and Share

1. Click **Save** (disk icon) → name: `Payment Pipeline Performance`
2. Click **Share** → copy link → send to team
3. Set auto-refresh → **10s** or **30s** (top-right dropdown) for live monitoring

### 5k. Add Alerts (Optional)

1. Open a panel (e.g., P95 panel) → **Alert** tab → **Create alert rule**
2. Example: Alert if P95 E2E latency > 100ms for 5 min:

```promql
histogram_quantile(0.95, rate(pipeline_stage_duration_seconds_bucket{stage="e2e_total"}[5m])) > 0.1
```

3. Set notification channel (Slack, email, PagerDuty)
4. Click **Save**

### 5l. If You Don't Have Dashboard Create Access

Give your platform team this ready-made request:

> **Request:** "Please create a Grafana dashboard for our app's custom Micrometer metrics.
> - Prometheus data source
> - Metric: `pipeline_stage_duration_seconds` (tagged by `stage`)
> - Panels: avg latency per stage, P95 per stage, throughput, e2e heatmap
> - Also include: `circuit_breaker_current_state` gauge
> - Pod exposes metrics on management port 9090 at path `/actuator/prometheus`
> - Scrape annotations are on the pod"

---

## Quick Decision Matrix

| Tool | Code Change? | Redeploy? | Where to See Data | Best For |
|---|---|---|---|---|
| **VisualVM** | No | No | Your laptop | One-time method hot spots |
| **Micrometer** | Yes | Yes | `curl /actuator/metrics` | Per-stage latency, feeds all tools |
| **App Insights** | No (auto) | No | Azure Portal | KQL histogram queries, Azure charts |
| **Prometheus** | Yes (dep) | Yes | Prometheus UI | Raw time-series queries |
| **Grafana** | No | No | Browser | Team dashboards, alerting, sharing |

---

## Section 6: Implementation Options Comparison

### Option A: Micrometer Timer Instrumentation (RECOMMENDED)

**What it is:** Add `Timer.Sample` / `Timer.record()` calls at each pipeline stage boundary in your actual Kafka Streams processor code.

**What you get:**
- Per-stage p50/p95/p99/max latencies at `/actuator/prometheus`
- Works in AKS immediately (just redeploy)
- Survives restarts, aggregates across pods
- Scraped by Prometheus/Grafana stack
- Auto-flows to Application Insights
- Zero external tooling dependency

**Where the timers go (2 files changed):**

```
PaymentsRecordProcessorSupplier.process()
  ├── Timer.Sample start              ← e2e start
  ├── breaker.tryAcquirePermission()
  ├── businessProcessorService.process()  ← wraps stages 2-6
  ├── breaker.record(result)
  └── e2eSample.stop(e2eTimer)        ← e2e end (in finally block)

DefaultBusinessProcessorService.process()
  ├── csfleTimer.record(() -> csfleCryptoService.normalizeAfterSerde())
  ├── businessLogicTimer.record(() -> { validation block })
  ├── dbAuditTimer.record(() -> exceptionAuditService.logSoftFailure())
  └── kafkaProduceTimer.record(() -> outputProducerService.send())
```

**Metrics emitted:**
```
pipeline_stage_duration_seconds{stage="e2e_total"}       — histogram
pipeline_stage_duration_seconds{stage="csfle_decrypt"}   — histogram
pipeline_stage_duration_seconds{stage="business_logic"}  — histogram
pipeline_stage_duration_seconds{stage="db_audit"}        — histogram
pipeline_stage_duration_seconds{stage="kafka_produce"}   — histogram
```

**Pros:** Production-safe, always-on, negligible overhead (nanosecond clock), data from all pods.

**Cons:** Requires code change + redeploy.

**Status: IMPLEMENTED** — See `PaymentsRecordProcessorSupplier.java` and `DefaultBusinessProcessorService.java`.

### Option B: VisualVM CPU Profiling (FASTEST for One-Off)

**What it is:** Port-forward JMX from an AKS pod and attach VisualVM for CPU sampling.

**JVM flags needed on pod:**
```
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.rmi.registry.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

**What you get:**
- Method-level CPU sampling (flame graph)
- See *which methods* are slow, but NOT per-record latency distribution
- Good for finding "is CSFLE decrypt 2ms or 200ms?"

**Pros:** No code changes, no redeploy. Immediate if JMX is already enabled.

**Cons:** Single pod only, no percentile distributions, sampling overhead unsafe under production load, no per-stage Kafka timing, JMX may need pod restart to enable.

**Status:** See Section 1 for full setup guide.

### Option C: JMeter + Structured Log Parsing (NOT RECOMMENDED)

**What it is:** JMeter hits a REST endpoint that publishes to Kafka, then parse structured logs for timing.

**Skip this** — JMeter is useful for load generation against Kafka topics but won't give you per-stage latency inside the stream processor. It's indirect and doesn't measure the actual pipeline stages.

### Recommendation: Do A + B in Parallel

| Action | Purpose |
|---|---|
| **Option A** — Micrometer Timers | Permanent, production-grade per-stage metrics |
| **Option B** — VisualVM on one pod | Quick one-off flame graph to spot hot methods |

Option A is already implemented in this project. Option B requires no code changes — just follow Section 1.

## Recommended Order

| Step | Action | Section |
|---|---|---|
| 1 | Pre-check: actuator, Prometheus, Grafana, App Insights | Section 0 |
| 2 | VisualVM CPU Sampler → find hot methods now | Section 1 |
| 3 | Add `micrometer-registry-prometheus` to pom.xml | Section 2a |
| 4 | Add percentile config to application.properties | Section 2b |
| 5 | Add pipeline stage timers to code | Section 2c |
| 6 | Redeploy → send load → verify `/actuator/metrics` | Section 2d-2e |
| 7 | Check App Insights `customMetrics` → run KQL | Section 3d |
| 8 | Open Grafana → Explore → verify metrics visible | Section 5b |
| 9 | Build Grafana dashboard panels | Section 5c-5h |
| 10 | Share dashboard with team | Section 5j |
