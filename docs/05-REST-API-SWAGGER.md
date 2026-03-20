# 05 — REST API & Swagger UI

> **Assignee:** _______________  
> **Scope:** REST controllers, Swagger/OpenAPI config, context-path, profile-based toggle  
> **Prereq:** Spring Boot with `spring-boot-starter-web` + `springdoc-openapi-starter-webmvc-ui`  

---

## API Architecture

```
Port 8080 — Business APIs (behind mTLS in K8s)
  └── context-path: /api/fs
       ├── /api/fs/hello?name=World         → HelloController (demo/health)
       ├── /api/fs/api/health               → HealthController (app status)
       ├── /api/fs/api/status               → HealthController (version info)
       ├── /api/fs/swagger-ui.html          → Swagger UI (dev/sit only)
       └── /api/fs/v3/api-docs              → OpenAPI JSON spec

Port 9090 — Management (NOT behind mTLS, see 03-HEALTH-PROBES.md)
       ├── /actuator/health/liveness        → K8s liveness probe
       ├── /actuator/health/readiness       → K8s readiness probe
       └── /actuator/prometheus             → Prometheus scraping
```

**Key:** All business REST controllers are on port 8080 under `/api/fs`. Actuator is on 9090 with no prefix. They are completely isolated.

---

## Maven Dependencies

```xml
<!-- pom.xml -->
<!-- Core web — provides embedded Tomcat, @RestController, etc. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Swagger UI + OpenAPI 3.0 spec generation -->
<!-- Automatically scans @RestController classes and generates interactive API docs -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.4</version>
    <!-- WHY 2.8.4: Latest stable for Spring Boot 3.x. Supports Java 21. -->
    <!-- springdoc-openapi-starter-webmvc-ui = Swagger UI + OpenAPI generation in one dep -->
</dependency>
```

---

## application.yml — Server & Swagger Config

```yaml
server:
  # Business API port. In K8s, Service routes ClusterIP:8080 → pod:8080.
  # Behind mTLS/SPIFFE via Istio — only allow-listed services can call.
  port: 8080

  servlet:
    # All REST controllers serve under /api/fs/*.
    # WHY: API gateway routing — /api/fs/* routes to this service.
    # Swagger UI: http://localhost:8080/api/fs/swagger-ui.html
    # API docs:   http://localhost:8080/api/fs/v3/api-docs
    context-path: /api/fs

# --- Springdoc OpenAPI / Swagger UI ---
# Enabled by default for local dev. Disabled in preprod/prod via Spring Profiles.
# In K8s: set SPRING_PROFILES_ACTIVE=prod (or preprod) to disable.
springdoc:
  api-docs:
    # OpenAPI JSON spec endpoint (used by Swagger UI internally)
    path: /v3/api-docs
  swagger-ui:
    # Interactive API testing page
    path: /swagger-ui.html

---
# Profile: preprod — disable Swagger
spring:
  config:
    activate:
      on-profile: preprod
springdoc:
  api-docs:
    enabled: false        # /v3/api-docs returns 404
  swagger-ui:
    enabled: false        # /swagger-ui.html returns 404

---
# Profile: prod — disable Swagger
spring:
  config:
    activate:
      on-profile: prod
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

### Profile Activation

| Environment | How to Set | Swagger |
|-------------|-----------|---------|
| Local dev | No profile (default) | **Enabled** |
| DEV / SIT | `SPRING_PROFILES_ACTIVE=dev` | **Enabled** |
| Pre-prod | `SPRING_PROFILES_ACTIVE=preprod` | **Disabled** |
| Production | `SPRING_PROFILES_ACTIVE=prod` | **Disabled** |

In K8s Deployment:
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
```

---

## REST Controllers

### HelloController — Simple Demo API

```java
package com.example.financialstream.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    // GET /api/fs/hello                → "HI World, How are you?"
    // GET /api/fs/hello?name=Alice     → "HI Alice, How are you?"
    //
    // @RequestParam with defaultValue makes the parameter optional.
    // Swagger UI auto-generates a form field for `name`.
    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "World") String name) {
        return "HI " + name + ", How are you?";
    }
}
```

**Note:** The `@GetMapping("/hello")` path is relative to the context-path. The full URL is `/api/fs/hello`.

### HealthController — Application Status APIs

```java
package com.example.financialstream.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")     // → full path: /api/fs/api/*
public class HealthController {

    // GET /api/fs/api/health → application health (business-level, not K8s probe)
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "kafka-streams-payment-processor");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    // GET /api/fs/api/status → application metadata
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> applicationStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("application", "kafka-streams-cb");
        response.put("version", "1.0.0");
        response.put("status", "RUNNING");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
```

**Important distinction:**
- `/api/fs/api/health` = Business-level health (custom controller on port 8080, behind mTLS)
- `/actuator/health/liveness` = K8s probe (actuator on port 9090, no mTLS)

These are NOT the same endpoint. The business health API is for callers like `coreui`. The actuator probe is for kubelet.

---

## How Swagger UI Works

Springdoc automatically:
1. Scans all `@RestController` classes in the Spring context
2. Generates an OpenAPI 3.0 JSON spec at `/api/fs/v3/api-docs`
3. Serves the Swagger UI at `/api/fs/swagger-ui.html`
4. Swagger UI reads the JSON spec and renders interactive API docs

No annotations needed on controllers for basic functionality. Springdoc discovers:
- `@GetMapping`, `@PostMapping`, etc. → API paths
- `@RequestParam`, `@PathVariable` → parameters with types and defaults
- `ResponseEntity<T>` → response schema

### Accessing Swagger

```
Local dev:   http://localhost:8080/api/fs/swagger-ui.html
K8s (dev):   kubectl port-forward svc/payments-stream 8080:8080
             → http://localhost:8080/api/fs/swagger-ui.html
             (Note: in K8s with mTLS, you need to port-forward to bypass Istio)
```

---

## API URL Summary

| URL | Port | Purpose | Auth |
|-----|------|---------|------|
| `/api/fs/hello` | 8080 | Demo endpoint | mTLS (S2S) |
| `/api/fs/api/health` | 8080 | Business health check | mTLS (S2S) |
| `/api/fs/api/status` | 8080 | App version/status | mTLS (S2S) |
| `/api/fs/swagger-ui.html` | 8080 | Interactive API docs | mTLS (S2S) |
| `/api/fs/v3/api-docs` | 8080 | OpenAPI JSON spec | mTLS (S2S) |
| `/actuator/health/liveness` | 9090 | K8s liveness probe | None |
| `/actuator/health/readiness` | 9090 | K8s readiness probe | None |
| `/actuator/prometheus` | 9090 | Prometheus metrics | None |

---

## Files to Create

```
src/main/java/com/example/financialstream/
├── controller/
│   ├── HelloController.java        # Simple GET with @RequestParam
│   └── HealthController.java       # Business-level health + status
src/main/resources/
└── application.yml                 # server.port, context-path, springdoc config
pom.xml                             # springdoc-openapi-starter-webmvc-ui dependency
```

---

## Verification Checklist

- [ ] `curl http://localhost:8080/api/fs/hello` → `HI World, How are you?`
- [ ] `curl http://localhost:8080/api/fs/hello?name=Alice` → `HI Alice, How are you?`
- [ ] `curl http://localhost:8080/api/fs/api/health` → JSON with `status: UP`
- [ ] `curl http://localhost:8080/api/fs/api/status` → JSON with `version: 1.0.0`
- [ ] `http://localhost:8080/api/fs/swagger-ui.html` → Swagger UI loads with all endpoints listed
- [ ] With `SPRING_PROFILES_ACTIVE=prod`: Swagger UI returns 404
- [ ] With `SPRING_PROFILES_ACTIVE=prod`: `/v3/api-docs` returns 404
- [ ] Port 8080 does NOT serve `/actuator/*` endpoints
- [ ] Port 9090 does NOT serve `/api/fs/*` endpoints
