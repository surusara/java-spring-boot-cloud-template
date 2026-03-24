# 04 — Service-to-Service Authentication (SPIFFE / Istio mTLS)

> **Assignee:** _______________  
> **Scope:** mTLS via SPIFFE, Istio AuthorizationPolicy, allow-list, port isolation  
> **Prereq:** Istio service mesh installed on AKS cluster, pods injected with Envoy sidecar  

---

## Istio Sidecar — Diagnostics & Validation

Before configuring S2S policies, confirm Istio is active on your pods and traffic flows through the mesh.

### What is `istio-proxy`?

When Istio injection is enabled for your namespace, every pod gets an extra sidecar container called `istio-proxy` (Envoy):

```
your-pod
├── your-app          ← your Spring Boot container (ports 8080, 9090)
└── istio-proxy       ← Envoy sidecar injected by Istio (intercepts all traffic)
```

### Check if Istio sidecar is injected

```bash
# List all containers in your pod — look for "istio-proxy"
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.spec.containers[*].name}'

# Or search pod description
kubectl describe pod <pod-name> -n <namespace> | grep -i istio
```

### Check namespace injection label

```bash
kubectl get namespace <namespace> --show-labels | grep istio
# Expected: istio-injection=enabled
```

### Check Istio / proxy version

```bash
# Control plane version
istioctl version

# Sidecar proxy version on a specific pod
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- pilot-agent version
```

### Verify traffic is flowing through Istio

```bash
# Envoy downstream connection stats (non-zero = traffic flows through mesh)
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- \
  curl -s localhost:15000/stats | grep downstream_cx_total

# Envoy upstream clusters (services your pod can reach)
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- \
  curl -s localhost:15000/clusters | grep health

# Proxy sync status across the mesh
istioctl proxy-status
```

### Test probe endpoint from sidecar (simulates kubelet)

```bash
# Curl the management port from inside the istio-proxy container.
# Both containers share the pod network, so localhost:9090 reaches your app.
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- \
  curl -s http://localhost:9090/actuator/health/liveness
# Expected: {"status":"UP"}
```

> **No Istio?** Use your app container name instead of `istio-proxy`, or omit `-c` entirely:
> ```bash
> kubectl exec <pod-name> -n <namespace> -- curl -s http://localhost:9090/actuator/health/liveness
> ```

### Quick Diagnostic Reference

| What | Command |
|------|---------|
| Sidecar injected? | `kubectl get pod <pod> -o jsonpath='{.spec.containers[*].name}'` |
| Namespace injection? | `kubectl get ns <ns> --show-labels \| grep istio` |
| Istio config issues? | `istioctl analyze -n <namespace>` |
| Proxy sync status | `istioctl proxy-status` |
| Full Envoy config | `istioctl proxy-config all <pod> -n <namespace>` |
| Listener routes | `istioctl proxy-config listener <pod> -n <namespace>` |
| Active connections | `kubectl exec <pod> -c istio-proxy -- curl -s localhost:15000/stats \| grep active` |
| Envoy access logs | `kubectl logs <pod> -c istio-proxy --tail=50` |

> **Tip:** If `istioctl` isn't installed locally, the `kubectl exec -c istio-proxy` commands still work — they run inside the cluster.

---

## What Is SPIFFE + mTLS?

SPIFFE (Secure Production Identity Framework For Everyone) gives every K8s workload a **cryptographic identity** — an X.509 certificate with a SPIFFE ID like:

```
spiffe://cluster.local/ns/financial-streams/sa/payments-stream
```

**mTLS** (mutual TLS) means both sides of a connection present certificates. Not just "the server proves who it is" (regular TLS), but "the client also proves who it is."

On AKS with Istio:
- Envoy sidecar auto-injects into every pod
- Envoy handles certificate issuance, rotation, and TLS termination
- Your Java code doesn't touch certificates at all — it's transparent

---

## Two-Layer Security Model

```
Layer 1: AUTHENTICATION (mTLS)              Layer 2: AUTHORIZATION (Policy)
"Who are you?"                               "Are you ALLOWED?"
┌────────────────────────────────┐           ┌────────────────────────────────┐
│ Every request must present a   │           │ Check caller's SPIFFE ID      │
│ valid SPIFFE SVID (X.509 cert) │    →      │ against an allow-list         │
│ No cert = connection refused   │           │ Not in list = 403 Forbidden   │
│ Invalid cert = TLS failure     │           │ DENY BY DEFAULT               │
└────────────────────────────────┘           └────────────────────────────────┘
```

**Key principle: DENY-BY-DEFAULT.** If you create an `ALLOW` policy, everything NOT listed is automatically denied. You do NOT need a separate blacklist.

---

## Port Architecture for S2S

```
┌─ Pod: payments-stream ──────────────────────────────┐
│                                                      │
│   Port 8080 (http)          Port 9090 (management)   │
│   ┌──────────────────┐      ┌──────────────────┐    │
│   │ Business APIs    │      │ Actuator          │    │
│   │ - /api/fs/hello  │      │ - /health/liveness│    │
│   │ - /api/fs/...    │      │ - /health/readiness│   │
│   │                  │      │ - /prometheus     │    │
│   │ PROTECTED by     │      │                   │    │
│   │ mTLS + AuthzPol  │      │ NOT protected     │    │
│   └──────────────────┘      │ (kubelet needs    │    │
│          ▲                  │  direct access)   │    │
│          │                  └──────────────────┘    │
│     Envoy sidecar               ▲                   │
│     intercepts 8080             │                   │
│     (NOT 9090)            kubelet / prometheus       │
└──────────────────────────────────────────────────────┘
```

**Why port 9090 bypasses mTLS:**
- Kubelet sends health probes but can't present SPIFFE certs
- Prometheus scraper needs direct access without auth negotiation
- Actuator endpoints don't expose business data — acceptable to leave unauthenticated on internal network

---

## Istio AuthorizationPolicy — Full Config

```yaml
# k8s-manifest.yaml (excerpt)

# --- S2S Authorization (SPIFFE / Istio mTLS) ---
# Deny-by-default: only listed principals can call this service's business APIs.
# Unlisted services → 403 Forbidden. No cert → TLS handshake fails.
# Port 9090 (management) is NOT affected — kubelet probes bypass the mesh.
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: payments-stream-allow-list
  namespace: financial-streams
spec:
  selector:
    matchLabels:
      app: payments-stream               # Applies to THIS service's pods

  # ALLOW = deny-by-default. Only matching rules pass.
  # If no rule matches → 403 Forbidden.
  action: ALLOW

  rules:
    - from:
        - source:
            # SPIFFE IDs of services allowed to call this API.
            # Format: cluster.local/ns/{namespace}/sa/{service-account}
            # The service account must match the caller pod's serviceAccountName.
            principals:
              - "cluster.local/ns/financial-streams/sa/coreui"
              - "cluster.local/ns/financial-streams/sa/admin-dashboard"
              # Add more as needed:
              # - "cluster.local/ns/other-namespace/sa/other-service"
      to:
        - operation:
            # Only protect the business API port.
            # Port 9090 (management) is NOT listed → not intercepted by this policy.
            ports: ["8080"]
```

---

## What Happens to Different Callers?

| Caller | SPIFFE ID | Port | Result |
|--------|-----------|------|--------|
| `coreui` (same namespace) | `cluster.local/ns/financial-streams/sa/coreui` | 8080 | **ALLOWED** ✅ |
| `admin-dashboard` (same namespace) | `cluster.local/ns/financial-streams/sa/admin-dashboard` | 8080 | **ALLOWED** ✅ |
| `reporting-svc` (same namespace) | `cluster.local/ns/financial-streams/sa/reporting-svc` | 8080 | **DENIED 403** ❌ |
| `payments-svc` (different namespace) | `cluster.local/ns/platform/sa/payments-svc` | 8080 | **DENIED 403** ❌ |
| No cert (external / curl) | — | 8080 | **TLS handshake fails** ❌ |
| Kubelet (health probe) | — | 9090 | **ALLOWED** ✅ (not in policy scope) |
| Prometheus (metrics scrape) | — | 9090 | **ALLOWED** ✅ (not in policy scope) |

**You do NOT need to blacklist others.** Any SPIFFE ID not in `principals` is automatically denied.

---

## Cross-Namespace Callers

If a service in a different namespace needs access:

```yaml
principals:
  - "cluster.local/ns/financial-streams/sa/coreui"            # same namespace
  - "cluster.local/ns/frontend-ns/sa/web-gateway"             # different namespace
  - "cluster.local/ns/monitoring-ns/sa/alerting-service"       # monitoring namespace
```

The namespace is encoded in the SPIFFE ID. Istio validates it cryptographically.

---

## Swagger UI and S2S

| Environment | Profile | Swagger on 8080 | mTLS on 8080 |
|-------------|---------|-----------------|--------------|
| Local dev | `default` | Enabled | No (no Istio locally) |
| DEV / SIT | `dev` | Enabled | Yes — caller must be in allow-list |
| Pre-prod | `preprod` | **Disabled** | Yes |
| Production | `prod` | **Disabled** | Yes |

In DEV/SIT with Swagger enabled: only `coreui` and `admin-dashboard` can reach Swagger UI (because it's on port 8080 behind mTLS). This is by design — developers use these service accounts for testing.

**Profile-based Swagger control** (in application.yml):

```yaml
# Default: Swagger enabled (local dev, dev, sit)
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html

---
# preprod/prod: Swagger disabled
spring:
  config:
    activate:
      on-profile: preprod
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

Set profile via K8s env:
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"           # or "preprod", "dev"
```

---

## ServiceAccount Setup

Every pod that calls your API needs its own ServiceAccount. The SA name becomes the SPIFFE identity.

```yaml
# For YOUR service (the one being called):
apiVersion: v1
kind: ServiceAccount
metadata:
  name: payments-stream               # → SPIFFE: .../sa/payments-stream
  namespace: financial-streams

---
# For the CALLER service (e.g., coreui):
apiVersion: v1
kind: ServiceAccount
metadata:
  name: coreui                         # → SPIFFE: .../sa/coreui
  namespace: financial-streams

# coreui's Deployment must reference this SA:
# spec.template.spec.serviceAccountName: coreui
```

---

## Istio PeerAuthentication — Enforce mTLS

Optional but recommended: enforce mTLS cluster-wide or per-namespace.

```yaml
# Enforce mTLS for all services in financial-streams namespace
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: strict-mtls
  namespace: financial-streams
spec:
  mtls:
    mode: STRICT        # No plaintext allowed — ALL traffic must be mTLS
    # Options:
    #   STRICT     → only mTLS accepted (recommended for financial)
    #   PERMISSIVE → accepts both mTLS and plaintext (migration mode)
    #   DISABLE    → mTLS disabled (testing only)
```

---

## Zero Code Changes Required

The beauty of Istio/SPIFFE:
- **No Java code changes** — Envoy sidecar handles all TLS transparently
- **No certificate management** — Istio auto-rotates certs (default 24h)
- **No SDK or library** — works with any language/framework
- Your Spring Boot app just listens on HTTP (8080) — Envoy terminates TLS before traffic reaches your app

---

## Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Deny-by-default | `action: ALLOW` | Only listed services can call — no blacklist needed |
| Port 8080 only | `ports: ["8080"]` | Port 9090 (management) must remain open for kubelet |
| Separate management port | 9090 | Kubelet can't present SPIFFE certs |
| STRICT mTLS | `PeerAuthentication` | Financial industry requirement — no plaintext |
| Profile-based Swagger | `SPRING_PROFILES_ACTIVE` | Disabled in preprod/prod, enabled in dev |

---

## Files to Create

```
k8s-manifest.yaml           # AuthorizationPolicy + PeerAuthentication (shown above)
                             # ServiceAccount (already exists)
```

No Java code changes — Istio operates at the network layer.

---

## Verification Checklist

- [ ] Istio sidecar injected: `kubectl describe pod <pod-name>` shows `istio-proxy` container
- [ ] `istioctl analyze -n financial-streams` — no errors
- [ ] From `coreui` pod: `curl http://payments-stream:8080/api/fs/hello` → 200 OK
- [ ] From unlisted pod: `curl http://payments-stream:8080/api/fs/hello` → 403 Forbidden
- [ ] Liveness probe: `kubectl logs <pod> -c istio-proxy` shows probe requests on 9090 (not intercepted)
- [ ] Prometheus scraping: ServiceMonitor targets show UP in Prometheus UI
- [ ] `kubectl get authorizationpolicy -n financial-streams` shows your policy

---

## Excluding Management Port 9090 from S2S / mTLS

### The Problem

AKS kubelet sends HTTP health probes (liveness + readiness) to your pod. The kubelet:
- **Cannot present a SPIFFE certificate** — it's a node-level component, not a mesh workload
- **Cannot negotiate mTLS** — it sends plain HTTP
- If the management port (9090) is behind mTLS/AuthorizationPolicy, **probes fail → pod restarts in a loop**

### Why Port 9090 Is Automatically Excluded from AuthorizationPolicy

The `AuthorizationPolicy` only targets `ports: ["8080"]`:

```yaml
rules:
  - from:
      - source:
          principals:
            - "cluster.local/ns/financial-streams/sa/coreui"
    to:
      - operation:
          ports: ["8080"]    # ← ONLY port 8080 is protected
                              # Port 9090 is NOT listed → policy does NOT apply
```

**Port 9090 is never mentioned** → the AuthorizationPolicy has no effect on it → kubelet probes pass.

### But: PeerAuthentication STRICT Mode Blocks 9090 Too

The `PeerAuthentication` with `mode: STRICT` applies to **ALL ports by default** — including 9090. This means kubelet's plain HTTP probes to port 9090 will get TLS handshake errors.

**Fix: Exclude port 9090 from mTLS enforcement using `portLevelMtls`:**

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: strict-mtls
  namespace: financial-streams
spec:
  selector:
    matchLabels:
      app: payments-stream
  mtls:
    mode: STRICT              # Default: all ports require mTLS
  portLevelMtls:
    9090:
      mode: PERMISSIVE        # Port 9090: accept both mTLS AND plaintext
                               # Kubelet sends plaintext → accepted
                               # Prometheus with mTLS → also accepted
```

### How the Two Policies Work Together

```
kubelet → port 9090 (liveness/readiness)
  ├─ PeerAuthentication: PERMISSIVE on 9090 → plaintext allowed ✅
  └─ AuthorizationPolicy: no rule for 9090 → no restriction ✅
  Result: PROBE PASSES ✅

Prometheus → port 9090 (metrics scrape)
  ├─ PeerAuthentication: PERMISSIVE on 9090 → plaintext or mTLS ✅
  └─ AuthorizationPolicy: no rule for 9090 → no restriction ✅
  Result: SCRAPE PASSES ✅

coreui → port 8080 (business API)
  ├─ PeerAuthentication: STRICT on 8080 → must present cert ✅
  └─ AuthorizationPolicy: coreui in principals list → allowed ✅
  Result: API CALL PASSES ✅

unknown-svc → port 8080 (business API)
  ├─ PeerAuthentication: STRICT on 8080 → cert presented ✅
  └─ AuthorizationPolicy: NOT in principals list → DENIED ❌
  Result: 403 FORBIDDEN ❌

curl (no cert) → port 8080 (business API)
  ├─ PeerAuthentication: STRICT on 8080 → no cert → TLS fails ❌
  Result: CONNECTION REFUSED ❌
```

### AKS Pod Spec — Health Probes on Port 9090

```yaml
# k8s-manifest.yaml (Deployment spec)
containers:
  - name: payments-stream
    ports:
      - containerPort: 8080    # Business APIs (mTLS protected)
        name: http
      - containerPort: 9090    # Management/Actuator (excluded from mTLS)
        name: management

    # --- Liveness Probe ---
    # Kubelet calls this on port 9090 (management).
    # If this fails → pod is RESTARTED.
    # Port 9090 is PERMISSIVE in PeerAuthentication → plaintext accepted.
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 9090              # ← management port, NOT 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      failureThreshold: 3

    # --- Readiness Probe ---
    # Kubelet calls this on port 9090 (management).
    # If this fails → pod is removed from Service endpoints (no traffic).
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 9090              # ← management port, NOT 8080
      initialDelaySeconds: 15
      periodSeconds: 10
      failureThreshold: 3

    # --- Startup Probe ---
    # Gives the app time to start before liveness kicks in.
    # Prevents liveness from killing a slow-starting pod.
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 9090              # ← management port, NOT 8080
      initialDelaySeconds: 10
      periodSeconds: 5
      failureThreshold: 30      # 30 × 5s = 150s max startup time
```

### Common Mistake: Probes on Port 8080

```yaml
# ❌ WRONG — probe on business port behind mTLS
livenessProbe:
  httpGet:
    path: /api/fs/actuator/health/liveness
    port: 8080                  # ← kubelet can't do mTLS → probe FAILS
                                # → pod restart loop!

# ✅ CORRECT — probe on management port excluded from mTLS
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 9090                  # ← kubelet sends plaintext → probe PASSES
```

### Prometheus ServiceMonitor — Scrape Port 9090

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: payments-stream-metrics
  namespace: financial-streams
spec:
  selector:
    matchLabels:
      app: payments-stream
  endpoints:
    - port: management         # ← matches containerPort name "management" (9090)
      path: /actuator/prometheus
      interval: 15s
      # No TLS config needed — port 9090 is PERMISSIVE
```

### Troubleshooting: Probes Failing After Enabling mTLS

| Symptom | Cause | Fix |
|---------|-------|-----|
| Pod stuck in `CrashLoopBackOff` | Liveness probe on port 8080 (mTLS blocks kubelet) | Move probe to port 9090 |
| Readiness probe fails, pod shows `0/1 Ready` | Readiness probe on port 8080 | Move probe to port 9090 |
| `upstream connect error or disconnect/reset before headers` in probe logs | PeerAuthentication STRICT on all ports including 9090 | Add `portLevelMtls: 9090: mode: PERMISSIVE` |
| Prometheus targets show DOWN | Scraping port 8080 instead of 9090 | Update ServiceMonitor to `port: management` (9090) |
| Probes work but return 404 | Wrong path — actuator context path differs from business API | Use `/actuator/health/liveness` (no `/api/fs` prefix on 9090) |

### Verification Commands

```bash
# 1. Verify probes use port 9090
kubectl get pod <pod> -o jsonpath='{.spec.containers[0].livenessProbe}' | jq
# Expect: "port": 9090

# 2. Verify PeerAuthentication has portLevelMtls for 9090
kubectl get peerauthentication strict-mtls -n financial-streams -o yaml | grep -A 3 portLevelMtls
# Expect: 9090: mode: PERMISSIVE

# 3. Test probe endpoint directly from node (simulates kubelet)
kubectl exec <pod> -c istio-proxy -- curl -s http://localhost:9090/actuator/health/liveness
# Expect: {"status":"UP"}

# 4. Verify AuthorizationPolicy only covers port 8080
kubectl get authorizationpolicy payments-stream-allow-list -n financial-streams -o yaml | grep -A 2 ports
# Expect: ports: ["8080"]  — 9090 NOT listed

# 5. Check Envoy listener config for port exclusion
istioctl proxy-config listener <pod> -n financial-streams | grep 9090
# Port 9090 should show PERMISSIVE or no policy attached
```
