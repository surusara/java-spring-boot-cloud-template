# JVM Tuning, GC, Container Image, and Startup Runbook

Audience: Spring Boot + Kafka Streams + AKS + Confluent Cloud teams with one week to go-live.

Scope: practical, low-risk tuning steps you can apply now, plus higher-risk options such as GraalVM native image that should usually be validated after go-live.

---

## 1) Prioritize changes by risk

### Safe to do before go-live
- Measure current heap, CPU, GC pause time, and startup time first.
- Set explicit CPU/memory requests and limits in AKS.
- Set JVM memory sizing intentionally instead of relying on defaults.
- Enable GC logging.
- Enable Micrometer/Actuator metrics.
- Reduce container image size with layered jars / buildpacks / smaller base image.
- Remove unused dependencies and dev tools from runtime image.
- Tune Kafka Streams thread count based on CPU.
- Consider lazy init only if startup time matters more than early failure detection.

### Medium risk
- Change GC algorithm.
- Change Xms/Xmx significantly.
- Enable virtual threads.
- Change thread pools, HTTP client pools, DB pool sizing.

### High risk for one-week go-live
- Switch from JVM jar to GraalVM native image.
- Large framework upgrades.
- Broad reactive rewrite.
- Aggressive JVM flags that are not backed by profiling.

Reason: Spring Boot and GraalVM both document that native images start faster and usually use less memory, but native images are a different deployment/runtime model and rely on ahead-of-time analysis plus compatibility metadata. That is valuable, but not usually the first thing to change one week before production. citeturn720411search3turn720411search7turn720411search14

---

## 2) Baseline what you have now

Record these before changing anything:
- Pod CPU usage and CPU throttling
- Pod memory working set and restart count
- JVM heap used / max heap
- GC pause count and total pause time
- Startup time to readiness
- Per-trade latency: p50, p95, p99
- API latency, DB latency, CSFLE timing
- Kafka lag

### Minimum baseline commands
```bash
kubectl top pod -n <ns>
kubectl describe pod <pod> -n <ns>
kubectl logs <pod> -n <ns> | grep -i -E "gc|pause|oom|killed"
```

### Add startup measurement in logs
Capture these timestamps:
- container start
- JVM start
- Spring Boot started
- readiness probe success
- first Kafka assignment

---

## 3) JVM memory sizing: Xms, Xmx, and container-aware percentages

Modern Java is container-aware, and Java 21 documents percentage-based heap sizing such as `-XX:MaxRAMPercentage` and `-XX:InitialRAMPercentage`. Java 21 documents the default `MaxRAMPercentage` as 25%. citeturn720411search8

### Practical recommendation for AKS
For containerized Spring Boot services, prefer **percentage-based sizing** first instead of hardcoding very large `-Xmx` values.

Example:
```bash
JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=30 -XX:MaxRAMPercentage=60"
```

This means:
- initial heap starts around 30% of container memory
- max heap can grow to around 60% of container memory
- remaining memory is left for metaspace, thread stacks, direct buffers, Netty, RocksDB, native libs, and OS page cache

### Why not set heap to 90%?
Because your app also needs non-heap memory for:
- metaspace
- direct memory
- native crypto libs
- Kafka client buffers
- RocksDB off-heap / page cache if used
- thread stacks

### When to use Xms = Xmx
Set `-Xms` close to `-Xmx` only if:
- you want highly predictable heap behavior
- your pod memory limit is stable
- you can afford reserved memory from startup

Useful for latency-sensitive services, but it increases memory reservation pressure.

Example:
```bash
JAVA_TOOL_OPTIONS="-Xms1g -Xmx1g"
```

### My practical go-live guidance
- Start with percentage-based memory on AKS unless you already have proven heap sizing.
- For a Kafka Streams service under load, often keep heap around **50% to 65%** of pod memory limit, not more, until you measure native/off-heap needs.
- Do not shrink heap so much that minor GC becomes constant.

---

## 4) GC tuning: what to use now

### Start with the default unless you have evidence
For modern JDKs, the default GC is usually a good starting point. Avoid random collector changes right before go-live unless you have measured pause problems.

### What to do immediately
Enable GC logging.

Example:
```bash
JAVA_TOOL_OPTIONS="-Xlog:gc*:stdout:time,uptime,level,tags"
```

That gives you:
- allocation pressure insight
- pause times
- frequency of young/full GC
- whether heap is too small

### What to look for in GC logs
- many frequent young GCs -> heap may be too small or object churn too high
- full GCs -> serious pressure, possible memory leak, poor sizing, or direct/native pressure
- long pauses -> collector or heap size may need tuning

### Should you switch GC?
Only if you find a real issue.

Examples:
- If pauses are already low and throughput is good, keep current GC.
- If latency is the main problem and heap is large, test another collector in non-prod first.

Do not change GC and heap sizing and pod limits all at once.

---

## 5) Threading and virtual threads

Spring Boot supports virtual threads via `spring.threads.virtual.enabled=true`, and the Spring Boot docs recommend also setting `spring.main.keep-alive=true` so the JVM stays alive even if all threads are virtual. Spring Boot also warns that pinned virtual threads can reduce throughput and suggests detection with JFR or `jcmd`. citeturn720411search9turn720411search15

### Recommendation
For your blocking reference-data REST calls, virtual threads are worth testing because they can improve concurrency without a full reactive rewrite.

### Minimal config
```properties
spring.threads.virtual.enabled=true
spring.main.keep-alive=true
```

### But measure these after enabling
- throughput
- CPU usage
- pinned virtual thread events
- DB pool saturation
- remote API saturation

### When not to rely on virtual threads alone
- heavy synchronized blocks
- CPU-bound work such as crypto on the hot path
- libraries that pin threads frequently

---

## 6) Startup-time tuning

Spring Boot documents lazy initialization, but also warns that it delays discovery of misconfiguration and can delay failures until first use. citeturn720411search6

### Low-risk startup improvements
- remove unnecessary auto-configurations
- remove unused starters
- disable devtools in runtime
- keep component scanning narrow
- delay non-critical background initialization

### Lazy init (use carefully)
```properties
spring.main.lazy-initialization=true
```

Use only if startup is a real problem and you accept that some failures move from startup time to first request or first message.

### Practical advice one week before go-live
Use lazy init only if:
- startup time is a proven issue
- you have enough smoke tests to hit critical paths after startup

Otherwise, keep eager init so failures happen early.

---

## 7) Container image size reduction

Spring Boot provides first-class support for building efficient container images and layered jars / buildpacks. citeturn720411search0

### Good immediate steps
1. Use Spring Boot layered jars or buildpacks.
2. Use a smaller runtime base image.
3. Use multi-stage Docker builds.
4. Copy only the final artifact into runtime image.
5. Remove build tools, package managers, caches, and test artifacts from runtime image.
6. Avoid shell utilities you do not need in production image.

### Example multi-stage Dockerfile for JVM
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

### Better container practice
Prefer buildpacks if your platform already supports them well, because Spring Boot explicitly supports efficient container image creation with Cloud Native Buildpacks. citeturn720411search0

---

## 8) GraalVM native image: where it helps and where it does not

Spring Boot and GraalVM state that native images generally start much faster and usually have a smaller memory footprint than JVM deployments. citeturn720411search1turn720411search3turn720411search7turn720411search10

### Where native image helps
- very fast startup
- lower memory footprint
- smaller attack surface
- bursty workloads, functions, scale-to-zero, short-lived jobs

### Where it may not be your first move
- heavy reflection/dynamic proxies not yet fully validated
- frequent need for heap dump / standard JVM diagnostics familiarity
- complex libraries requiring extra native metadata
- one week before go-live for a critical streaming system

### Recommendation for your timeline
Do **not** make GraalVM native image your main performance fix for this go-live unless:
- you already have a passing native-image pipeline
- you already ran integration and load tests on the native artifact
- your main pain is startup time, not steady-state per-trade latency

Why: your current bottlenecks sound more like API wait time, CSFLE cost, DB cost, and JVM/container sizing than startup alone.

---

## 9) Native image memory notes

GraalVM documents that native image memory behavior depends on the selected GC and runtime settings; for example, the Serial GC in native image can default max heap to 80% of physical memory if not specified. citeturn720411search12

So even with native image, memory still needs explicit tuning. Native image is not “no tuning required.”

---

## 10) Build-time and packaging optimizations

### Maven / packaging
- skip tests only in container build stage if CI already ran them
- use reproducible build settings
- remove unused transitive dependencies
- prefer one packaging path only

### Spring Boot packaging choices
- executable jar on JVM for lowest operational change
- layered jar / buildpacks for better image caching
- native image only after validation

---

## 11) Observability settings you should enable now

### GC logs
```bash
JAVA_TOOL_OPTIONS="-Xlog:gc*:stdout:time,uptime,level,tags"
```

### Heap info from Actuator / Micrometer
Expose:
- `jvm.memory.used`
- `jvm.memory.max`
- `jvm.gc.pause`
- `process.cpu.usage`
- `system.cpu.usage`
- `jvm.threads.live`

### JFR for focused investigations
Use JFR in non-prod or carefully in prod for short windows to inspect:
- allocation hotspots
- lock contention
- pinned virtual threads
- CPU hotspots
- socket wait

---

## 12) Concrete recommended settings for first pass

### Option A: conservative JVM setup for go-live
```bash
JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=60 -Xlog:gc*:stdout:time,uptime,level,tags"
```

Use this when:
- you want safer memory headroom
- you use Kafka + DB + crypto + possible off-heap/native memory

### Option B: more fixed heap behavior
```bash
JAVA_TOOL_OPTIONS="-Xms1024m -Xmx1024m -Xlog:gc*:stdout:time,uptime,level,tags"
```

Use this only when:
- pod memory is known
- heap need is known
- you want predictable heap behavior

### Optional Spring settings for virtual threads test
```properties
spring.threads.virtual.enabled=true
spring.main.keep-alive=true
```

### Optional startup setting if startup is critical
```properties
spring.main.lazy-initialization=true
```
Use carefully because it can delay failures. citeturn720411search6

---

## 13) What to test in order this week

### Day 1
- Enable Micrometer/Actuator JVM metrics
- Enable GC logging
- Capture baseline CPU, memory, GC, startup, per-trade latency

### Day 2
- Right-size pod requests/limits
- Set JVM memory sizing explicitly
- Re-test

### Day 3
- Check allocation hotspots with VisualVM or JFR
- Look for excessive object creation, JSON/Avro churn, crypto churn

### Day 4
- Test virtual threads in lower environment if blocking REST is dominant
- Measure pinned thread events, throughput, DB connection usage

### Day 5
- Reduce container image size and improve startup packaging
- Validate no runtime dependencies were removed accidentally

### Day 6
- Freeze risky changes
- Keep only proven improvements

### Day 7
- Go-live with observability enabled
- Keep rollback path simple

---

## 14) What I would and would not change before your go-live

### I would change now
- explicit memory sizing
- GC logging
- metrics
- CPU/memory resource tuning in AKS
- image cleanup / layered image
- possibly virtual threads if tests are good

### I would probably not change now
- switch to GraalVM native image for the main service
- large GC collector experiments without evidence
- huge Xmx jumps without checking off-heap and pod limit headroom

---

## 15) Fast decision rules

### If GC pauses are low but CPU is high
Likely not a GC problem. Look at crypto, serialization, API waits, DB waits.

### If many young GCs happen
Heap may be too small, or allocation churn too high.

### If pod is OOMKilled but heap looks fine
Non-heap/native/off-heap memory may be the problem.

### If startup is slow but steady-state is acceptable
Image reduction and startup tuning help more than deep runtime tuning.

### If steady-state per-trade latency is high
Fix API/DB/CSFLE path before chasing startup optimizations.

