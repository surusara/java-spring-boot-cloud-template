# Build Optimization & WebClient Migration Guide

**Project:** Kafka Streams Circuit Breaker — Financial Payments  
**Environments:** DEV → TEST → PROD  
**Stack:** Java 21 · Spring Boot 3.5 · AKS · GitLab CI · Helm Charts

---

## Part 1 — Safe Build Optimizations (No Runtime Changes)

These steps reduce the production image size without changing application behavior.  
None of these touch Helm charts, `values-*.yaml`, ConfigMaps, or deployment templates.

---

### Step 1: Move Swagger to Maven Profile (saves ~5–8 MB in prod JAR)

Swagger UI bundles JavaScript/CSS/HTML assets inside the JAR. The prod Kafka Streams processor
has no need for an API testing UI — it's a stream processor, not a REST service.

**Current state:** `springdoc-openapi-starter-webmvc-ui` is a top-level dependency shipped to all environments.

**Target state:** Included in DEV and TEST only. Physically absent from the prod JAR.

#### 1.1 Remove springdoc from top-level `<dependencies>`

```xml
<!-- DELETE this block from the top-level <dependencies> section -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.4</version>
</dependency>
```

#### 1.2 Add three Maven profiles

Add this block inside `<project>` in `pom.xml`, after `</build>`:

```xml
<profiles>
    <!-- DEV — Swagger ON, tests run -->
    <profile>
        <id>dev</id>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
        <dependencies>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>2.8.4</version>
            </dependency>
        </dependencies>
    </profile>

    <!-- TEST — Swagger ON, tests run (QA verifies APIs via Swagger UI) -->
    <profile>
        <id>test</id>
        <dependencies>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>2.8.4</version>
            </dependency>
        </dependencies>
    </profile>

    <!-- PROD — Swagger excluded, no UI assets shipped -->
    <profile>
        <id>prod</id>
        <!-- No springdoc dependency — not compiled, not shipped -->
    </profile>
</profiles>
```

#### 1.3 Update `.gitlab-ci.yml` build commands

```yaml
# DEV — Swagger included, tests run
build-dev:
  stage: build
  script:
    - mvn clean package -Pdev
  only:
    - develop

# TEST — Swagger included, tests run
build-test:
  stage: build
  script:
    - mvn clean package -Ptest
  only:
    - /^release\/.*$/

# PROD — Swagger excluded, tests already passed in test stage
build-prod:
  stage: build
  script:
    - mvn clean package -Pprod -DskipTests
  only:
    - main
    - /^hotfix\/.*$/
```

#### 1.4 Verify

```bash
# Build with prod profile
mvn clean package -Pprod -DskipTests

# Confirm springdoc is absent
jar tf target/kafka-streams-cb-1.0.0.jar | grep springdoc
# Expected: no output

# Build with dev profile
mvn clean package -Pdev

# Confirm springdoc is present
jar tf target/kafka-streams-cb-1.0.0.jar | grep springdoc
# Expected: BOOT-INF/lib/springdoc-openapi-*.jar
```

**Risk:** None. Swagger is already disabled at runtime in prod via Spring profile config.
This step just removes the dead bytes from the JAR.

---

### Step 2: Exclude `tomcat-embed-websocket` (saves ~2–3 MB)

`spring-boot-starter-web` transitively pulls `tomcat-embed-websocket`. This project has:
- Zero `@MessageMapping` usage
- Zero WebSocket / STOMP / SockJS code
- REST controllers use plain HTTP `@GetMapping` only
- Actuator health probes are HTTP-only

WebSocket removal does **not** affect REST, WebClient, RestTemplate, or any HTTP-based communication.

#### 2.1 Add exclusion to `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.apache.tomcat.embed</groupId>
            <artifactId>tomcat-embed-websocket</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

#### 2.2 Verify

```bash
mvn dependency:tree | grep websocket
# Expected: no output

# Smoke test — all endpoints still work
mvn clean package -Pdev
java -jar target/kafka-streams-cb-1.0.0.jar --spring.kafka.bootstrap-servers=localhost:9092

# Test endpoints
curl http://localhost:8080/api/fs/hello
curl http://localhost:8080/api/fs/api/health
curl http://localhost:9090/actuator/health
```

**Risk:** None. No WebSocket code exists in this project.

---

### Step 3: Add `.dockerignore` (faster Docker build, no size change)

Create `.dockerignore` in the same directory as the Dockerfile:

```
.git/
docs/
*.md
k8s-manifest.yaml
docker-compose.yml
helmchart/
src/
!target/
```

Docker sends only `target/*.jar` to the build daemon instead of the entire project folder.

**Risk:** None. Build context shrinks from ~100MB+ to ~1MB.

---

### Step 4: Helm Chart `pom.xml` — Clarification

Helm does **not** need a `pom.xml`. Helm charts are pure YAML (`Chart.yaml`, `values.yaml`, `templates/`).

| Possible reason for `helmchart/pom.xml` | Is it needed? |
|---|---|
| Maven Helm plugin (`helm-maven-plugin`) to run `helm package` via Maven | No — `.gitlab-ci.yml` already runs `helm` commands directly |
| Version sync between app and chart via Maven property filtering | Convenient but not required — use `--set` or CI variables instead |
| Multi-module Maven parent includes it as `<module>helmchart</module>` | Check root `pom.xml` — if not referenced, it's dead weight |
| Copy-paste artifact from another project | Safe to remove |

**Action:** Check if `helmchart/pom.xml` is referenced anywhere in `.gitlab-ci.yml` or a parent `pom.xml`. If not, it can be removed. Helm does not read it.

---

## Part 2 — WebClient Migration for Reference Data Calls

You're planning to add external reference data lookups from within the Kafka Streams processor.
WebClient (Reactor Netty) is the right choice over RestTemplate for this workload.

---

### Why WebClient for Kafka Streams

| Factor | RestTemplate | WebClient |
|---|---|---|
| Thread model | Blocks the calling thread during HTTP call | Non-blocking I/O on Netty event loop |
| Stream thread impact | Kafka Streams thread blocked waiting for HTTP response | Stream thread freed during network wait (when used correctly) |
| Connection pooling | Per-request or basic pooling | Built-in connection pool with Reactor Netty |
| Timeout control | Coarser (connect + read) | Fine-grained (connect, read, write, response) |
| Retry/backoff | Manual implementation | Built-in `.retryWhen(Retry.backoff(...))` |
| Future | Maintenance mode since Spring 6.1 | Actively developed, recommended by Spring team |

**Important caveat for Kafka Streams:** Kafka Streams `process()` is synchronous — you must call
`.block()` on the `Mono` to get the result before returning. The benefit is still real: Netty's
connection pooling, timeout handling, and retry logic are superior to RestTemplate's, even in
blocking mode.

---

### Step 1: Add `spring-boot-starter-webflux` Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> **Note:** Adding `webflux` alongside `spring-boot-starter-web` does NOT switch your app to
> reactive. Spring Boot detects Tomcat (from `starter-web`) and stays in servlet mode.
> WebClient is available as an HTTP client without changing the server stack.

---

### Step 2: Create WebClient Configuration

```java
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class ReferenceDataClientConfig {

    @Bean
    public WebClient referenceDataWebClient() {
        HttpClient httpClient = HttpClient.create()
                // Connection pool: reuse connections (critical for high-throughput)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)    // 1s connect timeout
                .responseTimeout(Duration.ofSeconds(2))                  // 2s response timeout
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(2, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(2, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl("http://reference-data-service")   // K8s service name
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
```

---

### Step 3: Create Reference Data Service

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
public class ReferenceDataService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);

    private final WebClient referenceDataWebClient;
    private final MeterRegistry meterRegistry;

    public ReferenceDataService(WebClient referenceDataWebClient, MeterRegistry meterRegistry) {
        this.referenceDataWebClient = referenceDataWebClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Fetch reference data for a given key.
     * Called from Kafka Streams processor — MUST block to return result synchronously.
     */
    public ReferenceData fetch(String referenceKey) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ReferenceData result = referenceDataWebClient.get()
                    .uri("/api/reference/{key}", referenceKey)
                    .retrieve()
                    .bodyToMono(ReferenceData.class)
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                            .maxBackoff(Duration.ofSeconds(1))
                            .filter(this::isRetryable))
                    .block(Duration.ofSeconds(3));    // Hard deadline — stream thread must not block forever

            sample.stop(Timer.builder("trade.reference.api")
                    .tag("status", "success")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            return result;

        } catch (Exception ex) {
            sample.stop(Timer.builder("trade.reference.api")
                    .tag("status", "error")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            log.error("Reference data fetch failed for key={}: {}", referenceKey, ex.getMessage());
            throw ex;
        }
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status >= 500;   // Retry on rate-limit or server errors only
        }
        return t instanceof java.net.ConnectException
            || t instanceof java.util.concurrent.TimeoutException;
    }
}
```

---

### Step 4: Integrate into Kafka Streams Processor

In your `process()` method (inside the `Processor` or `ProcessorSupplier`), call the service:

```java
// Inside process() — runs on Kafka Streams thread
ReferenceData refData = referenceDataService.fetch(record.key());

// Enrich the payment record
enrichedPayment.setCurrency(refData.getCurrency());
enrichedPayment.setCounterparty(refData.getCounterpartyName());
```

---

### Step 5: Configure Timeouts per Environment

Add to `application.yml` (or per-environment `values-*.yaml` in Helm):

```yaml
app:
  reference-data:
    base-url: http://reference-data-service     # K8s service DNS
    connect-timeout-ms: 1000
    response-timeout-ms: 2000
    read-timeout-ms: 2000
    retry-max-attempts: 2
    retry-initial-backoff-ms: 100
```

Then externalize the WebClient config:

```java
@ConfigurationProperties(prefix = "app.reference-data")
public record ReferenceDataProperties(
    String baseUrl,
    int connectTimeoutMs,
    int responseTimeoutMs,
    int readTimeoutMs,
    int retryMaxAttempts,
    int retryInitialBackoffMs
) {}
```

#### Per-environment Helm values

**`values-dev.yaml`**
```yaml
referenceData:
  baseUrl: http://reference-data-service.dev.svc.cluster.local
  responseTimeoutMs: 5000       # Relaxed for dev
  retryMaxAttempts: 1
```

**`values-test.yaml`**
```yaml
referenceData:
  baseUrl: http://reference-data-service.test.svc.cluster.local
  responseTimeoutMs: 3000
  retryMaxAttempts: 2
```

**`values-prod.yaml`**
```yaml
referenceData:
  baseUrl: http://reference-data-service.prod.svc.cluster.local
  responseTimeoutMs: 2000       # Strict for prod
  retryMaxAttempts: 2
```

---

### Step 6: Circuit Breaker Impact — Reference Data Failures

Decide how reference data failures interact with your existing circuit breaker:

| Failure type | Should it count toward circuit breaker? | Rationale |
|---|---|---|
| Timeout (2s exceeded) | **Yes** — soft business failure | Trade cannot be enriched → downstream impact |
| 4xx client error | **No** — log and skip | Bad request from our side, retrying won't help |
| 5xx server error (after retries) | **Yes** — soft business failure | Persistent downstream failure |
| Network unreachable | **Yes** — soft business failure | Infrastructure issue |

This aligns with your existing 3-tier exception classification:
- Reference data failures are **Tier 2 (soft business failures)** → counted by circuit breaker
- If failure rate exceeds 20% over 30 minutes, circuit breaker opens → Kafka Streams stops

---

### Step 7: Dependency Summary After Migration

```xml
<!-- After all changes, your pom.xml dependencies will be: -->

<!-- Core -->
spring-boot-starter
spring-boot-starter-web          (with tomcat-embed-websocket excluded)
spring-boot-starter-webflux      (NEW — for WebClient only, does NOT change server to reactive)
spring-boot-starter-actuator
spring-boot-starter-validation
spring-kafka
kafka-streams
resilience4j-circuitbreaker
resilience4j-micrometer
jackson-databind

<!-- Dev/Test only (via Maven profile) -->
springdoc-openapi-starter-webmvc-ui

<!-- Test scope -->
spring-boot-starter-test
spring-kafka-test
```

---

## Execution Order — Safe Rollout

| Step | Change | Where | Risk | Rollback |
|---|---|---|---|---|
| 1 | Move Swagger to Maven profiles | `pom.xml` + `.gitlab-ci.yml` | None | Revert `pom.xml` |
| 2 | Exclude `tomcat-embed-websocket` | `pom.xml` | None | Remove `<exclusion>` |
| 3 | Add `.dockerignore` | New file alongside Dockerfile | None | Delete file |
| 4 | Verify Helm `pom.xml` is unused | Check with devops | None | No change if used |
| 5 | Add `spring-boot-starter-webflux` | `pom.xml` | Low | Remove dependency |
| 6 | Implement `ReferenceDataService` with WebClient | New Java classes | Medium | Feature flag / remove |
| 7 | Wire into Kafka Streams processor | Processor code | Medium | Revert processor |

**Steps 1–4:** Do first. Pure build optimization, zero runtime risk. Deploy all three environments.

**Steps 5–7:** Do second. New feature (reference data calls). Roll through DEV → TEST → PROD.
