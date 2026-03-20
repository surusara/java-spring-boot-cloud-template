# Kafka Streams Performance Tuning Runbook for Go-Live

**Scope**
Java Spring Boot + Kafka Streams + Confluent Cloud + Schema Registry + CSFLE + AKS

**Goal for this week**
Break the current ~2000 ms per-trade path into measurable stages, identify the biggest bottleneck, and improve throughput without destabilizing go-live.

---

## 1. What to do first: build a timing breakdown per trade

Do **not** start by tuning Kafka blindly. First split the total trade time into these measurable steps:

1. Kafka record received / deserialize start
2. CSFLE decrypt / serde cost
3. Reference data API call
4. Business logic / enrichment
5. DB save / update
6. Kafka produce / serialize / encrypt
7. End-to-end total

### 1.1 Recommended timer names

Use these exact metric names so they are easy to find later:

- `trade.total`
- `trade.decrypt`
- `trade.reference.api`
- `trade.business`
- `trade.db`
- `trade.encrypt`
- `trade.produce`
- `trade.payload.bytes`
- `trade.error`

If you can tag metrics safely, use low-cardinality tags only:

- `flow=trade-processing`
- `status=success|error`
- `operation=enrich|save|publish`
- `service=reference-data`

Avoid high-cardinality tags such as trade ID, customer ID, key ID, schema ID, or correlation ID.

---

## 2. Fastest path to get metrics visible in Spring Boot

## 2.1 Add dependencies

Add Actuator and Micrometer. Example Maven dependencies:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

If Prometheus is not available yet, keep Actuator at least. You can still inspect metrics with `/actuator/metrics`.

## 2.2 Minimal config to expose metrics

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: trade-processing-service
```

If you only want a safer first step in lower environments:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

## 2.3 Optional percentile / histogram config

This is very useful because average time is not enough. You want p50, p95, p99.

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        trade.total: true
        trade.reference.api: true
        trade.db: true
        trade.decrypt: true
        trade.encrypt: true
        trade.produce: true
      percentiles:
        trade.total: 0.5,0.95,0.99
        trade.reference.api: 0.5,0.95,0.99
        trade.db: 0.5,0.95,0.99
        trade.decrypt: 0.5,0.95,0.99
        trade.encrypt: 0.5,0.95,0.99
        trade.produce: 0.5,0.95,0.99
      slo:
        trade.total: 100ms,250ms,500ms,1s,2s,5s
        trade.reference.api: 50ms,100ms,250ms,500ms,1s,2s
        trade.db: 25ms,50ms,100ms,250ms,500ms,1s
```

If property binding for custom metric names behaves differently in your version, define histogram / percentile behavior programmatically with a `MeterFilter`.

Example:

```java
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    MeterFilter tradeMetricFilter() {
        return MeterFilter.maximumAllowableTags("trade.total", "status", 10, MeterFilter.deny());
    }
}
```

---

## 3. How to time each step in code

You can do this immediately in one service without redesigning the application.

## 3.1 Simple helper pattern using `Timer.Sample`

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

@Component
public class TimedStepRunner {

    private final MeterRegistry meterRegistry;

    public TimedStepRunner(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T record(String metricName, Callable<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = action.call();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "success")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            return result;
        } catch (Exception ex) {
            sample.stop(Timer.builder(metricName)
                    .tag("status", "error")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            throw new RuntimeException(ex);
        }
    }

    public void recordVoid(String metricName, Runnable action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            action.run();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "success")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        } catch (Exception ex) {
            sample.stop(Timer.builder(metricName)
                    .tag("status", "error")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            throw ex;
        }
    }
}
```

## 3.2 Apply the helper inside the trade processing flow

```java
public void processTrade(TradeEvent event) {
    Timer.Sample total = Timer.start(meterRegistry);

    try {
        EncryptedTrade encryptedTrade = timedStepRunner.record("trade.decrypt", () -> decrypt(event));

        ReferenceData ref = timedStepRunner.record("trade.reference.api", () ->
                referenceDataClient.fetch(encryptedTrade.getReferenceKey()));

        EnrichedTrade enriched = timedStepRunner.record("trade.business", () ->
                enrich(encryptedTrade, ref));

        timedStepRunner.recordVoid("trade.db", () -> tradeRepository.save(enriched));

        timedStepRunner.recordVoid("trade.produce", () -> kafkaPublisher.publish(enriched));

        total.stop(Timer.builder("trade.total")
                .tag("status", "success")
                .publishPercentileHistogram()
                .register(meterRegistry));

    } catch (Exception ex) {
        total.stop(Timer.builder("trade.total")
                .tag("status", "error")
                .publishPercentileHistogram()
                .register(meterRegistry));
        throw ex;
    }
}
```

This gives you immediate visibility into where time is actually going.

---

## 4. How to isolate CSFLE cost

You usually do not get a ready-made metric named `csfle.decrypt.ms` out of the box. The safest practical approach one week before go-live is to measure the code block where decrypt/deserialize happens and the block where serialize/encrypt happens.

### 4.1 What to measure

Measure these separately if your code path allows it:

- Deserialize only
- Deserialize + decrypt
- Serialize only
- Serialize + encrypt

If serializer internals are hidden by Kafka SerDes, use outer step timing:

- input consume/decode block → `trade.decrypt`
- output encode/send block → `trade.encrypt`

### 4.2 Where to place timers

Examples:

```java
EncryptedTrade trade = timedStepRunner.record("trade.decrypt", () -> decrypt(event));
```

and

```java
timedStepRunner.recordVoid("trade.encrypt", () -> kafkaPublisher.publish(enriched));
```

If `publish()` includes network send, break it down further:

- `trade.serialize.encrypt`
- `trade.kafka.send`

### 4.3 Extra checks specific to CSFLE

Create a short checklist:

- Count how many fields are encrypted in the schema.
- Verify whether all encrypted fields are required on this path.
- Check whether you decrypt the full object even when only one field is needed.
- Check whether the message is decrypted and then re-encrypted multiple times in a single flow.
- Check whether schema evolution added extra encrypted fields recently.

### 4.4 What good evidence looks like

After timing, compare these:

- `trade.decrypt` p50 / p95 / p99
- `trade.encrypt` p50 / p95 / p99
- Payload size before and after enrichment

If decrypt or encrypt is consistently a big chunk of `trade.total`, then do not waste the week only tuning partitions or pod count.

---

## 5. How to isolate reference data API cost

Because your flow calls the reference data API per trade, this is a likely major bottleneck.

## 5.1 Add client-side timing first

Wrap the API client call:

```java
ReferenceData ref = timedStepRunner.record("trade.reference.api", () ->
        referenceDataClient.fetch(referenceKey));
```

## 5.2 Capture HTTP status counts

You should also count how many calls succeed, timeout, or fail.

```java
Counter.builder("trade.reference.api.calls")
        .tag("status", "success")
        .register(meterRegistry)
        .increment();
```

For errors:

```java
Counter.builder("trade.reference.api.calls")
        .tag("status", "timeout")
        .register(meterRegistry)
        .increment();
```

## 5.3 Add timeout config now

If you are using `RestTemplate`, at minimum configure connect and read timeout.

Example with Apache HttpClient factory:

```java
@Bean
public RestTemplate restTemplate() {
    var factory = new HttpComponentsClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setConnectionRequestTimeout(1000);
    factory.setReadTimeout(2000);
    return new RestTemplate(factory);
}
```

If you are using `WebClient`:

```java
@Bean
public WebClient referenceDataWebClient() {
    HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(2))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);

    return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .baseUrl("http://reference-data-service")
            .build();
}
```

## 5.4 What to compare

Compare:

- API call p50 / p95 / p99
- timeout count
- error count
- in-flight concurrent requests
- CPU and memory on the reference data service

If `trade.reference.api` dominates total time, your tuning week should focus there first.

---

## 6. How to isolate DB cost

If DB is in the hot path, instrument it explicitly.

## 6.1 Time the repository call

```java
timedStepRunner.recordVoid("trade.db", () -> tradeRepository.save(enriched));
```

If there are multiple DB operations, split them:

- `trade.db.lookup`
- `trade.db.insert`
- `trade.db.update`
- `trade.db.commit`

## 6.2 Capture pool metrics

If you use HikariCP, Spring Boot already exposes many pool metrics through Micrometer once Actuator is enabled.

Look for:

- active connections
- idle connections
- pending threads waiting for connection
- connection acquire time

These metrics can quickly tell you if the DB pool is saturated.

## 6.3 Add SQL timing if needed

If the repository timer shows DB is slow but you do not know which SQL is bad, use one of these only in lower / controlled environments:

- datasource-proxy
- p6spy
- DB native slow query log

For a one-week go-live window, DB native slow query logs are often the safest if available.

## 6.4 What to look for

- Is `trade.db` flat or spiky?
- Are there lock waits?
- Is the connection pool exhausted?
- Are inserts slow because of indexes, triggers, or per-row commits?
- Is enrichment causing larger payloads to be persisted than needed?

---

## 7. How to isolate Kafka produce / send cost

Do not assume all output cost is encryption. Kafka produce can also add time.

Split if possible:

- `trade.serialize.encrypt`
- `trade.kafka.send`

Example:

```java
timedStepRunner.recordVoid("trade.serialize.encrypt", () -> {
    // build output payload / serialize if this is explicit in your code
});

timedStepRunner.recordVoid("trade.kafka.send", () -> kafkaTemplate.send(topic, key, payload).get());
```

If you call `.get()` on the producer future, you are forcing synchronous wait. That is acceptable for measurement in test, but note that it increases latency.

If you cannot split, at least measure the full publish block as `trade.produce`.

Also monitor producer-related metrics in Kafka client / Actuator if already exposed.

---

## 8. How to isolate business logic cost

Once API, DB, decrypt, and produce are separately measured, whatever remains is usually pure app logic.

Use:

```java
EnrichedTrade enriched = timedStepRunner.record("trade.business", () ->
        enrich(encryptedTrade, ref));
```

If `trade.business` is high, profile the JVM next.

---

## 9. Use VisualVM and JFR to find CPU hotspots quickly

One week before go-live, use **sampling**, not heavy profiling.

## 9.1 What to use where

- **VisualVM Sampler**: very good for local / lower environments to see top CPU methods and allocation behavior.
- **JFR**: best for production-like runs because overhead is generally low.

## 9.2 Local / test steps with VisualVM

1. Start the service with realistic workload.
2. Open VisualVM.
3. Attach to the JVM.
4. Start CPU Sampler.
5. Run your load for 5 to 10 minutes.
6. Capture snapshot.
7. Review top hot methods.

Look for hotspots under:

- crypto classes
- serializer / deserializer
- JSON / Avro conversion
- HTTP client stack
- JDBC driver
- object mapping
- synchronized blocks

## 9.3 JFR steps

Start the app with JFR enabled, or trigger recording via `jcmd`.

Example command:

```bash
jcmd <pid> JFR.start name=trade-profile settings=profile duration=10m filename=/tmp/trade-profile.jfr
```

After completion:

```bash
jcmd <pid> JFR.dump name=trade-profile filename=/tmp/trade-profile.jfr
```

Open the `.jfr` file in VisualVM or JDK Mission Control.

## 9.4 What to inspect in JFR

- Method profiling / execution hotspots
- Socket read / write time
- Allocation rate
- GC pauses
- Thread states
- Monitor blocked events
- Virtual thread pinning if you are testing virtual threads

---

## 10. Check AKS resource bottlenecks before rewriting code

Sometimes the application is fine but the pod is CPU-throttled or memory-pressured.

## 10.1 What to collect

From Kubernetes / Azure dashboards or Prometheus:

- CPU usage per pod
- CPU throttling
- memory usage
- restarts / OOMKilled
- node pressure
- network latency / errors

## 10.2 Quick interpretation

- High CPU + low throughput: CPU-heavy bottleneck, likely crypto, serialization, business code, or GC.
- Low CPU + high latency: usually waiting on API, DB, Kafka ack, or locks.
- Spiky latency + normal average: check p95/p99, pool exhaustion, lock contention, GC, or API tail latency.

## 10.3 JVM options to verify

For Java 21 on containers, confirm you are not under-sizing heap or over-constraining CPU.

At minimum record the runtime flags and pod limits used during tests.

---

## 11. Add a short payload-size metric

Payload size often correlates with serde and CSFLE cost.

```java
DistributionSummary.builder("trade.payload.bytes")
        .baseUnit("bytes")
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(serializedPayload.length);
```

Track separately if useful:

- `trade.input.payload.bytes`
- `trade.output.payload.bytes`

If output payload is much bigger than input payload, encryption and produce costs can grow materially.

---

## 12. Suggested one-week execution plan

## Day 1: Instrumentation

Do these first:

- Add Actuator + Micrometer
- Expose `metrics` and optionally `prometheus`
- Add `trade.total`, `trade.decrypt`, `trade.reference.api`, `trade.business`, `trade.db`, `trade.produce`
- Add payload-size metric
- Add API success / timeout / error counters

Deliverable by end of Day 1:

- `/actuator/metrics` shows your custom metrics
- one test run completed with metrics emitted

## Day 2: Collect baseline

Run a load test or replay realistic message volume.

Collect:

- p50 / p95 / p99 for each step
- pod CPU / memory
- API timeout counts
- DB pool metrics
- consumer lag

Deliverable by end of Day 2:

- a small table showing where the 2000 ms is actually spent

Suggested table format:

| Step | p50 | p95 | p99 | Notes |
|---|---:|---:|---:|---|
| trade.decrypt |  |  |  |  |
| trade.reference.api |  |  |  |  |
| trade.business |  |  |  |  |
| trade.db |  |  |  |  |
| trade.produce |  |  |  |  |
| trade.total |  |  |  |  |

## Day 3: Profile hottest step

If API is hottest:
- inspect client/server latency
- add timeouts
- check thread pool / connection pool
- consider virtual threads for blocking REST path

If DB is hottest:
- inspect pool usage
- check slow SQL
- verify index / lock / batch strategy

If crypto / serde is hottest:
- count encrypted fields
- measure payload size
- review redundant decrypt/re-encrypt steps

## Day 4: Apply low-risk improvements

Only low-risk changes before go-live:

- timeout tuning
- connection pool sizing
- Hikari pool tuning
- remove unnecessary synchronous waits
- split publish timing
- fix obviously slow SQL or missing index
- increase pod CPU if currently throttled
- reduce logging verbosity in hot path

## Day 5: Validate

Repeat the same load test.

Compare before vs after:

- `trade.total` p50/p95/p99
- throughput per pod
- lag
- error / timeout counts

## Day 6: Stabilize

Do not add major rewrites now unless the current design is completely blocked.

Focus on:

- rollback plan
- config freeze
- metric dashboard saved
- alert thresholds documented

## Day 7: Go-live readiness check

Before release, make sure you can answer these:

- What is the hottest step?
- What is expected throughput per pod?
- What p95 trade time is acceptable?
- What API timeout is configured?
- What DB pool size is configured?
- Which metric confirms backlog is building?
- Which metric confirms crypto cost is high?

---

## 13. Optional low-risk configuration ideas to consider now

## 13.1 Enable virtual threads for Spring Boot experiments

If you are already on Java 21 and your Spring Boot version supports it, test in lower environment only first:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Use this for blocking REST / DB style workloads only after measurement. Check for pinned virtual threads in JFR if you test this.

## 13.2 Hikari example

Tune only if DB pool is proven bottleneck.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 1000
      validation-timeout: 1000
      idle-timeout: 300000
      max-lifetime: 1800000
```

Do not raise pool size blindly. Align with DB capacity.

## 13.3 Log level hygiene

Heavy debug logging in hot path can distort latency.

For go-live week, ensure business hot path is not logging full payloads per trade unless absolutely required.

---

## 14. What not to do this week

Avoid these unless absolutely necessary:

- full migration to WebFlux across services
- large Kafka partition redesign without testing
- major schema redesign right before go-live
- aggressive caching redesign without correctness validation
- unbounded async / thread creation without metrics

---

## 15. Simple interpretation guide

### Case A: `trade.reference.api` is largest
Focus on:
- HTTP timeout
- caller concurrency model
- reference service capacity
- virtual threads or non-blocking client
- reducing one-call-per-trade pattern later

### Case B: `trade.db` is largest
Focus on:
- pool saturation
- slow SQL
- commit strategy
- indexes / locks
- save volume per trade

### Case C: `trade.decrypt` or `trade.encrypt` is largest
Focus on:
- number of encrypted fields
- payload size
- repeated decrypt/re-encrypt
- serializer overhead
- CPU sizing

### Case D: `trade.business` is largest
Focus on:
- CPU profiling
- object creation
- expensive mapping
- contention / locks
- algorithmic inefficiency

### Case E: `trade.total` is high but substeps look small
Usually this means one of:
- instrumentation gap
- waiting between steps
- thread scheduling / queueing
- lock contention
- producer blocking not separately measured
- external latency not fully included

---

## 16. Minimum dashboard to prepare before go-live

At minimum chart these:

1. `trade.total` p50 / p95 / p99
2. `trade.reference.api` p95 / p99
3. `trade.db` p95 / p99
4. `trade.decrypt` and `trade.encrypt`
5. error count by step
6. payload size histogram
7. consumer lag
8. pod CPU / memory / restarts
9. DB connection pool active / pending
10. API timeout count

---

## 17. Final recommendation for this week

Do this in order:

1. Instrument first.
2. Measure one realistic run.
3. Identify the single biggest step.
4. Fix that step with low-risk changes.
5. Re-measure.
6. Freeze configs and keep dashboards ready for go-live.

Do not optimize based on guesswork. Your real gain will come from seeing whether the 2000 ms is mainly:

- waiting on reference API,
- DB time,
- CSFLE / serialization,
- or CPU-heavy business logic.

---

## References

- Spring Boot Actuator and observability docs
- Micrometer timer and histogram docs
- Confluent CSFLE and Schema Registry docs
- VisualVM and JFR documentation
