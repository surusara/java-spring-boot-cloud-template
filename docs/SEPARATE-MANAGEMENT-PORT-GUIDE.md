# Separate Management Port for Health Probes — Quick Reference

## Why?
Keep actuator/health endpoints on a private port (e.g., `9091`) so production traffic on the main port (e.g., `8080`) is not mixed with K8s probe traffic.

---

## 1. Spring Boot Config
**File:** `src/main/resources/application.properties` (or `application.yml`)

```properties
# Main app port
server.port=8080

# Separate management port
management.server.port=9091

# Expose only health endpoint
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always

# Enable liveness & readiness probes (Spring Boot 2.3+)
management.endpoint.health.probes.enabled=true
management.health.livenessstate.enabled=true
management.health.readinessstate.enabled=true
```

**Result — these endpoints now live on port `9091`:**

| Endpoint | URL |
|---|---|
| Overall health | `http://localhost:9091/actuator/health` |
| Liveness | `http://localhost:9091/actuator/health/liveness` |
| Readiness | `http://localhost:9091/actuator/health/readiness` |

---

## 2. Kubernetes Deployment — Pod Spec
**File:** `k8s/deployment.yaml` (or your Helm `templates/deployment.yaml`)

```yaml
containers:
  - name: my-app
    ports:
      - containerPort: 8080        # app traffic
        name: http
      - containerPort: 9091        # management/probes
        name: management
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 9091                 # <-- management port, NOT 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      failureThreshold: 3
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 9091                 # <-- management port, NOT 8080
      initialDelaySeconds: 15
      periodSeconds: 5
      failureThreshold: 3
```

---

## 3. Kubernetes Service
**File:** `k8s/service.yaml` (or Helm `templates/service.yaml`)

Only expose the **app port**. Management port stays internal to the pod.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app
spec:
  selector:
    app: my-app
  ports:
    - port: 8080
      targetPort: 8080
      name: http
  # Do NOT expose 9091 externally
```

---

## 4. Dockerfile
**File:** `Dockerfile`

```dockerfile
EXPOSE 8080 9091
```

---

## 5. Service-to-Service (S2S) Calls — Do You Need to Change Anything?

**No.** The management port separation has **zero impact** on S2S / peer-to-peer communication.

| Concern | Answer |
|---|---|
| S2S calls between microservices | Use app port `8080` as before — **no change** |
| WebClient / RestTemplate base URLs | Still point to `http://peer-service:8080` — **no change** |
| Service mesh (Istio/Linkerd) sidecars | Proxy app port only — **no change** |
| mTLS between services | Configured on app port — **no change** |
| K8s Service DNS for peer discovery | Resolves to app port — **no change** |

**Rule:** Management port `9091` is only for **kubelet probes** (node-local). All service-to-service traffic stays on the app port `8080`. Leave S2S settings intact.

---

## 6. Checklist — What Changes (with filenames)

| # | File | What to change |
|---|---|---|
| 1 | `application.properties` | Add `management.server.port=9091` |
| 2 | `application.properties` | Add `management.endpoint.health.probes.enabled=true` |
| 3 | `application.properties` | Add `management.health.livenessstate.enabled=true` |
| 4 | `application.properties` | Add `management.health.readinessstate.enabled=true` |
| 5 | `deployment.yaml` | Add `containerPort: 9091` under `ports` |
| 6 | `deployment.yaml` | Change `livenessProbe.httpGet.port` → `9091` |
| 7 | `deployment.yaml` | Change `readinessProbe.httpGet.port` → `9091` |
| 8 | `service.yaml` | **No change** — do NOT expose management port |
| 9 | `ingress.yaml` | **No change** — routes only to app port |
| 10 | `networkpolicy.yaml` | Allow kubelet → pod on `9091` (if policies exist) |
| 11 | `Dockerfile` | `EXPOSE 8080 9091` |
| 12 | `keda-scaledobject.yaml` | If scraping health endpoints, update port to `9091` |
| 13 | S2S / WebClient config | **No change** — stays on app port `8080` |

---

## 7. Verify Locally

```bash
curl http://localhost:8080/actuator/health          # should FAIL (404)
curl http://localhost:9091/actuator/health           # {"status":"UP"}
curl http://localhost:9091/actuator/health/liveness  # {"status":"UP"}
curl http://localhost:9091/actuator/health/readiness # {"status":"UP"}
```

---

## Common Pitfalls

- **Probes still pointing to `8080`** → K8s marks pod as unhealthy → restart loop.
- **Forgot `management.endpoint.health.probes.enabled=true`** → `/liveness` and `/readiness` return 404.
- **Exposing `9091` in Service/Ingress** → health endpoints become public (security risk).
- **NetworkPolicy blocking kubelet → `9091`** → probes fail, pod keeps restarting.
- **Changing S2S URLs to `9091`** → Don't. S2S stays on app port. Management port is probes only.
