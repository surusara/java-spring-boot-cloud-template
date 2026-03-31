# Code Citations

## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```


## License: unknown
https://github.com/reflectoring/reflectoring.github.io/blob/1099158fe8a60ae58a49885e52e41f55c5066d7b/_posts/2020-09-05-spring-boot-docker.md

```
## Faster Build, Sonar Scan & Deployment Pipeline Design

Based on your current setup (Spring Boot 3.x, Java 21, Maven, multi-stage Docker, GitLab CI, Helm + AKS), here's an overall design for a faster pipeline:

---

### Current Pipeline (Sequential)

```
build → test → sonar → package → publish → deploy
~25-40 min total
```

### Optimized Pipeline Design

```
                  ┌─ unit tests ──────────┐
build (cached) ──►├─ sonar scan ──────────├──► docker build (layered) → publish → deploy
                  └─ integration tests ───┘
         ~3-5min        ~parallel              ~2-3min        ~1min     ~2min
                        ~5-8min
```

**Target: 25-40 min → 10-15 min**

---

### 1. Faster Maven Build

| Technique | Savings | How |
|-----------|---------|-----|
| **Maven Daemon (mvnd)** | 30-50% | Keeps JVM warm across builds — `mvnd clean package` |
| **Parallel module builds** | 20-30% | `mvn -T 1C` (1 thread per CPU core) |
| **.m2 cache across pipelines** | 2-5 min | Already doing this in GitLab CI (`cache: .m2/repository`) ✅ |
| **Skip unnecessary plugins in CI** | 10-20s | `-Dcheckstyle.skip -Denforcer.skip -Djacoco.skip` during build stage |
| **Profile-based Swagger exclusion** | 5-8 MB | Already documented in [06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md](java-spring-boot-cloud-template/docs/06-BUILD-OPTIMIZATION-AND-WEBCLIENT-MIGRATION.md) ✅ |

```yaml
# GitLab CI — faster build stage
build:
  script:
    - mvn clean package -DskipTests -T 1C -Pprod -Drevision=${APP_VERSION}
```

---

### 2. Faster Sonar Scan

| Technique | Savings | How |
|-----------|---------|-----|
| **Run Sonar in parallel with tests** | 3-5 min | Separate stage that runs concurrently |
| **Incremental analysis** | 40-60% | `-Dsonar.incremental=true` on PR/MR branches |
| **Narrow scan scope** | 30-50% | `-Dsonar.inclusions=src/main/java/**` (skip test code analysis) |
| **Cache Sonar scanner** | 30-60s | Cache `~/.sonar/cache` in CI |

```yaml
# Parallel test + sonar stages
test:
  stage: verify
  script:
    - mvn test -Drevision=${APP_VERSION}
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml

sonar:
  stage: verify          # same stage = parallel execution
  script:
    - mvn sonar:sonar
        -Dsonar.projectKey=${CI_PROJECT_NAME}
        -Dsonar.host.url=${SONAR_HOST_URL}
        -Dsonar.token=${SONAR_TOKEN}
        -Dsonar.qualitygate.wait=true
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
  cache:
    paths:
      - .sonar/cache
```

**PR/MR builds — incremental scan only:**
```yaml
sonar-pr:
  script:
    - mvn sonar:sonar
        -Dsonar.pullrequest.key=${CI_MERGE_REQUEST_IID}
        -Dsonar.pullrequest.branch=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}
        -Dsonar.pullrequest.base=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}
```

---

### 3. Faster Docker Build

Your current Dockerfile in [reports-scheduler/Dockerfile](reports-scheduler/Dockerfile) already does multi-stage ✅. Add these optimizations:

| Technique | Savings | How |
|-----------|---------|-----|
| **Pre-built JAR (skip Maven in Docker)** | 3-5 min | Build JAR in CI, `COPY` into runtime image |
| **Spring Boot layered JARs** | 60-80% rebuild | Only rebuild changed layers |
| **BuildKit cache mounts** | 2-3 min | Mount Maven cache as BuildKit cache |
| **`.dockerignore`** | 10-30s | Reduce build context |

**Optimized Dockerfile (pre-built JAR approach — fastest):**

```dockerfile
# No build stage needed — JAR comes from CI pipeline
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Spring Boot layered extraction for better caching
WORKDIR /app
COPY target/reports-scheduler.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# Each layer cached independently — only changed layers rebuild
COPY --from=0 /app/dependencies/ ./
COPY --from=0 /app/spring-boot-loader/ ./
COPY --from=0 /app/snapshot-dependencies/ ./
COPY --from=0 /app/application/ ./

USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Enable layered JARs in `pom.xml`:**
```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

This means
```

