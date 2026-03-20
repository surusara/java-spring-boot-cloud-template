# JVM Memory Tuning — Fixed Heap Approach for AKS

**Project:** Kafka Streams Circuit Breaker — Financial Payments  
**Runtime:** Java 21 · Spring Boot 3.5 · AKS · Confluent Cloud  
**Workload:** 3M trades/day · Kafka Streams with circuit breaker

---

## Decision

**Fixed heap sizing (`Xms=Xmx`)** over percentage-based (`MaxRAMPercentage`).

---

## Production JVM Settings

```bash
JAVA_TOOL_OPTIONS="\
  -Xms1G \
  -Xmx1G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:MaxMetaspaceSize=256m \
  -XX:MaxDirectMemorySize=256m \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:stdout:time,uptime,level,tags"
```

### Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: payments-stream-config
  namespace: financial-streams
data:
  JAVA_TOOL_OPTIONS: >-
    -Xms1G
    -Xmx1G
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:MaxMetaspaceSize=256m
    -XX:MaxDirectMemorySize=256m
    -XX:+ExitOnOutOfMemoryError
    -Xlog:gc*:stdout:time,uptime,level,tags
```

### Kubernetes Deployment Resource Limits

```yaml
resources:
  requests:
    cpu: 1000m
    memory: 1.5Gi
  limits:
    cpu: 2000m
    memory: 2Gi
```

---

## Memory Budget — Every Byte Accounted For

```
┌──────────────────────────────────────────────────────────┐
│              Pod Memory Limit: 2048 MB                   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Java Heap (Xms = Xmx)               1024 MB  (50%)     │
│  ──────────────────────────────────────────────           │
│  Metaspace (capped)                    256 MB  (12.5%)   │
│  Direct Memory / Netty / Kafka (capped) 256 MB (12.5%)  │
│  Thread Stacks (~50 threads × 1MB)      50 MB  (2.5%)   │
│  JVM Internal (code cache, GC structs) 100 MB  (5%)     │
│  OS / Safety Buffer                    362 MB  (17%)     │
│                                                          │
│  Total:                               2048 MB  (100%)   │
└──────────────────────────────────────────────────────────┘
```

---

## Flag-by-Flag Explanation

### `-Xms1G -Xmx1G` — Fixed Heap

| Aspect | Detail |
|---|---|
| **What it does** | Allocates 1GB heap at startup, prevents heap resizing |
| **Why fixed** | No GC warmup cost. Kafka Streams processes trades at full speed from first message. Critical for KEDA scale-up — new pods must drain consumer lag immediately |
| **Why 1GB** | 50% of 2Gi pod limit. Leaves 1GB guaranteed for non-heap consumers (metaspace, Kafka buffers, thread stacks, direct memory, JVM internals) |
| **What to watch** | `jvm.memory.used` under load. If consistently >900MB, heap is too small. If <500MB at peak, heap could be reduced |

### `-XX:+UseG1GC` — Garbage Collector

| Aspect | Detail |
|---|---|
| **What it does** | Uses the G1 (Garbage-First) collector |
| **Why G1** | Default for Java 21 on heaps ≥ 512MB. Region-based, concurrent, low-pause. Well-suited for 1GB heap with mixed allocation patterns (Kafka records, Jackson objects, enrichment data) |
| **Threads** | G1 auto-sizes `ParallelGCThreads` from cgroup CPU limit. With `cpu: 2000m`, JVM sees 2 cores → 2 GC threads. Do NOT set `ActiveProcessorCount=1` — it forces single-threaded GC |

### `-XX:MaxGCPauseMillis=200` — GC Pause Target

| Aspect | Detail |
|---|---|
| **What it does** | Tells G1 to target ≤200ms pause times. G1 adjusts region sizes and collection frequency to meet this |
| **Why 200ms** | Conservative target for financial payment processing. Short enough to keep p99 latency acceptable, relaxed enough that G1 doesn't over-collect |
| **Fine-tuning** | After perf testing, if observed pauses are consistently <50ms, tighten to 100ms. If G1 cannot meet 200ms, heap may be too small or allocation rate too high |

### `-XX:MaxMetaspaceSize=256m` — Metaspace Cap

| Aspect | Detail |
|---|---|
| **What it does** | Caps class metadata storage at 256MB. Without this, metaspace grows unbounded |
| **Why 256MB** | Spring Boot 3.5 + Kafka Streams + Resilience4j + Jackson + Spring Web typically uses 100–180MB of metaspace. 256MB provides headroom without risk of unbounded growth |
| **What it prevents** | Classloader leaks (rare but catastrophic). A misconfigured library loading classes repeatedly would eventually OOMKill the pod without this cap |
| **What to watch** | `jvm.memory.used{area=nonheap, id=Metaspace}`. If it approaches 256MB, investigate classloading |

### `-XX:MaxDirectMemorySize=256m` — Off-Heap Buffer Cap

| Aspect | Detail |
|---|---|
| **What it does** | Caps `ByteBuffer.allocateDirect()` at 256MB. Used by Kafka clients and Reactor Netty (WebClient) |
| **Why it matters now** | Kafka producer/consumer use direct buffers for network I/O. Your config has `buffer-memory: 67108864` (64MB) for the producer alone |
| **Why it matters later** | When you add WebClient for reference data calls, Reactor Netty allocates direct buffers for HTTP connections. Without a cap, a connection pool leak could push pod memory past the limit |
| **What to watch** | `jvm.buffer.memory.used{id=direct}`. If approaching 256MB, check for buffer leaks |

### `-XX:+ExitOnOutOfMemoryError` — Fail-Fast on OOM

| Aspect | Detail |
|---|---|
| **What it does** | JVM exits immediately with status code 3 when any `OutOfMemoryError` is thrown |
| **Why critical** | Without this, the JVM may enter an inconsistent state — partially processing trades, not responding to health probes, but not restarting. Kubelet only restarts the pod if the process exits or health probes fail |
| **AKS behavior** | JVM exits → container exits → kubelet detects exit → restarts pod → clean recovery |
| **Alternative considered** | `HeapDumpOnOutOfMemoryError` — useful but requires writable volume for dump files and adds delay before exit. For a Kafka Streams processor, fast restart is more valuable than a heap dump. Enable only in non-prod for debugging |

### `-Xlog:gc*:stdout:time,uptime,level,tags` — GC Logging

| Aspect | Detail |
|---|---|
| **What it does** | Logs all GC events to stdout with timestamps, uptime, level, and tag metadata |
| **Why stdout** | AKS collects container stdout/stderr via the logging agent. GC logs appear in your centralized logging (Azure Monitor / Grafana / ELK) alongside application logs |
| **Performance cost** | Essentially zero. GC logging is asynchronous and buffered |
| **What to look for** | Frequent young GC → allocation churn or heap too small. Full GC → serious pressure, possible leak. Long pauses → collector or heap needs adjustment |

---

## What Was Removed from the Previous Prod Setup and Why

### `ActiveProcessorCount=1` — Removed

| Aspect | Detail |
|---|---|
| **What it did** | Forced the JVM to believe it had 1 CPU core |
| **Why it's wrong** | Your pod has `cpu: 2000m` (2 cores). Java 21 correctly reads cgroup v2 CPU limits on AKS. Overriding to 1 forces single-threaded G1 GC → longer pauses. Also reduces JIT compiler threads (slower warmup), `ForkJoinPool` size (hurts parallel operations), and Netty event loop threads (hurts WebClient throughput) |
| **When to use** | Only if the JVM misreads CPU on your cluster. Java 21 on AKS cgroup v2 does not have this problem. Verify with: `Runtime.getRuntime().availableProcessors()` via actuator |

### `InitialRAMPercentage=25` — Replaced with `Xms=1G`

| Aspect | Detail |
|---|---|
| **What it did** | Started heap at 25% of pod memory (512MB on 2Gi) |
| **Why replaced** | Caused GC thrashing during startup as heap grew from 512MB to max. Kafka Streams processor needs full heap immediately to drain consumer lag after KEDA scale-up |

### `MaxRAMPercentage=80` — Replaced with `Xmx=1G`

| Aspect | Detail |
|---|---|
| **What it did** | Allowed heap up to 80% of pod memory (1.6GB on 2Gi) |
| **Why replaced** | Left only 410MB for non-heap — dangerously close to OOMKill under load when Kafka buffers, metaspace, and thread stacks are accounted for. Fixed `Xmx=1G` guarantees 1GB for non-heap |
| **Hidden risk** | If someone changes pod memory limit from 2Gi to 3Gi, heap silently jumps to 2.4GB. With fixed sizing, heap stays at 1G regardless of pod limit changes |

---

## Percentage-Based vs Fixed Heap — Decision Matrix

| Factor | Percentage-based | Fixed (`Xms=Xmx`) | Winner for your workload |
|---|---|---|---|
| Predictability | Heap changes if pod limit changes | Heap is constant | **Fixed** |
| Non-heap budget | Implicit — whatever's left | Explicit — guaranteed | **Fixed** |
| Startup GC behavior | Warmup GC as heap grows | No warmup — full heap from start | **Fixed** |
| Cross-env portability | One flag for all pod sizes | Must set per environment | Percentage |
| Many services, varied sizes | One setting fits all | Separate Xmx per service | Percentage |
| Debugging OOMKill | Heap was "somewhere between 25–80%" | Heap was exactly 1GB | **Fixed** |
| KEDA scale-up latency | GC warmup on new pods | Full speed immediately | **Fixed** |

**Percentage-based is better for:** platform teams managing dozens of lightweight REST microservices with varied pod sizes.

**Fixed is better for:** dedicated services with known memory characteristics, high throughput requirements, and latency sensitivity — which is exactly your Kafka Streams circuit breaker.

---

## Monitoring After Deployment

### Key Metrics to Track

| Metric | Where | What to look for |
|---|---|---|
| `jvm.memory.used{area=heap}` | Grafana / Prometheus | Should stay <900MB at peak. If consistently >900MB, increase Xmx and pod limit |
| `jvm.memory.used{area=nonheap}` | Grafana / Prometheus | Metaspace + code cache. Should be <300MB |
| `jvm.buffer.memory.used{id=direct}` | Grafana / Prometheus | Direct buffers. Should stay well below 256MB cap |
| `jvm.gc.pause{action=end of minor GC}` | Grafana / Prometheus | Young GC pauses. p99 should be <100ms |
| `jvm.gc.pause{action=end of major GC}` | Grafana / Prometheus | Full GC. Should be **zero** in steady state. Any full GC is a red flag |
| `process.cpu.usage` | Grafana / Prometheus | Per-pod CPU. If >80% sustained, consider more stream threads or more pods |
| `container_memory_working_set_bytes` | Kubernetes metrics | Actual RSS. If close to 2Gi limit, pod is at risk of OOMKill |
| GC log output | Centralized logging | Look for full GC events, long pauses, or allocation failure |

### Quick Health Check Commands

```bash
# Pod memory and CPU usage
kubectl top pod -n financial-streams -l app=payments-stream

# Check for OOMKill events
kubectl get events -n financial-streams --field-selector reason=OOMKilling

# Check JVM actuator metrics
curl http://<pod-ip>:9090/actuator/metrics/jvm.memory.used?tag=area:heap
curl http://<pod-ip>:9090/actuator/metrics/jvm.memory.used?tag=area:nonheap
curl http://<pod-ip>:9090/actuator/metrics/jvm.gc.pause
curl http://<pod-ip>:9090/actuator/metrics/jvm.buffer.memory.used

# Verify CPU detection (should return 2, not 1)
curl http://<pod-ip>:9090/actuator/metrics/system.cpu.count
```

---

## Fine-Tuning After Performance Testing

| If you observe | Then adjust |
|---|---|
| Heap consistently <500MB at peak | Reduce `Xmx` to 768m, reduce pod limit to 1.5Gi |
| Heap consistently >900MB | Increase `Xmx` to 1280m, increase pod limit to 2.5Gi |
| Young GC pauses >200ms | Tighten `MaxGCPauseMillis` to 150ms or increase heap |
| Full GC events in steady state | Heap too small — increase `Xmx`. Or investigate memory leak |
| Metaspace approaching 256MB | Classloader leak — investigate library or framework issue |
| Direct memory approaching 256MB | Kafka or Netty buffer leak — investigate connection management |
| `ActiveProcessorCount` mismatch | Run `system.cpu.count` actuator metric — should match container CPU limit |
| Pod OOMKilled but heap looks fine | Non-heap memory exceeded budget. Check direct buffers, thread count, metaspace |
| GC pauses already <50ms consistently | Tighten `MaxGCPauseMillis` to 100ms for better p99 latency |

---

## Helm ConfigMap Update

Update the `JAVA_TOOL_OPTIONS` value in your Helm `values-prod.yaml`:

```yaml
javaToolOptions: >-
  -Xms1G
  -Xmx1G
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:MaxMetaspaceSize=256m
  -XX:MaxDirectMemorySize=256m
  -XX:+ExitOnOutOfMemoryError
  -Xlog:gc*:stdout:time,uptime,level,tags
```

No changes required to Dockerfile, Helm templates, deployment.yaml, or service.yaml.  
Only the ConfigMap value changes.
