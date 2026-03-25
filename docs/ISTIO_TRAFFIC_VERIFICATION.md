# Istio Traffic Flow Verification Checklist

> Quick-reference guide to verify that routes and endpoints are configured correctly and traffic is flowing from `istio-proxy` to your application pod.

---

## Prerequisites

- `kubectl` access to the cluster
- Target namespace and pod name known
- S2S `AuthorizationPolicy` removed (unrestricted access)

---

## 1. Verify the Request Reaches Envoy

Check the istio-proxy sidecar logs for your request:

```bash
kubectl logs <pod-name> -n <namespace> -c istio-proxy | grep "POST\|GET\|404\|503"
```

### Envoy Response Flags

| Flag | Meaning | Action |
|------|---------|--------|
| `-` | Success (no flag) | Request handled normally |
| `NR` | No Route | Envoy can't match the route — check VirtualService or host |
| `UH` | Upstream Unhealthy | App not running or not responding |
| `UF` | Upstream Connection Failure | App crashed or wrong port |
| `RBAC` | RBAC Access Denied | AuthorizationPolicy still blocking |
| `DC` | Downstream Connection Termination | Client disconnected |
| `URX` | Upstream Retry Limit Exceeded | App too slow or failing repeatedly |

---

## 2. Verify Your App Is Listening on the Right Port

```bash
# Check what port your container exposes
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.spec.containers[?(@.name!="istio-proxy")].ports[*].containerPort}'

# Confirm app is actually listening inside the pod
kubectl exec <pod-name> -n <namespace> -c <app-container> -- curl -s localhost:<port>/actuator/health
```

**Expected:** `{"status":"UP"}` or similar healthy response.

If the app isn't listening, Envoy will log `UH` or `UF` flags.

---

## 3. Verify the Kubernetes Service Matches

### Check Service Port Mapping

```bash
kubectl get svc <service-name> -n <namespace> -o yaml | grep -A5 ports
```

Confirm:
- `targetPort` matches your container's listening port
- `port` matches what callers use
- `protocol` is correct (TCP/HTTP)

### Check Endpoints Are Populated

```bash
kubectl get endpoints <service-name> -n <namespace>
```

- **If endpoints show `<none>`:** The Service selector doesn't match your pod labels.
- **Fix:** Compare `svc.spec.selector` with `pod.metadata.labels`.

```bash
# Compare labels
kubectl get svc <service-name> -n <namespace> -o jsonpath='{.spec.selector}'
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.metadata.labels}'
```

---

## 4. Verify No AuthorizationPolicy Is Blocking

```bash
# List all auth policies in your namespace (should be empty after delete)
kubectl get authorizationpolicy -n <namespace>

# IMPORTANT: Also check mesh-wide policies
kubectl get authorizationpolicy -n istio-system
```

> **Warning:** A `DENY`-all policy in `istio-system` overrides namespace-level deletions.

If any remain, inspect them:

```bash
kubectl get authorizationpolicy <policy-name> -n <namespace> -o yaml
```

---

## 5. Verify PeerAuthentication (mTLS Mode)

```bash
kubectl get peerauthentication -n <namespace>
kubectl get peerauthentication -n istio-system
```

| Mode | Behavior |
|------|----------|
| `STRICT` | Only mTLS traffic allowed — caller **must** have a sidecar |
| `PERMISSIVE` | Both plaintext and mTLS accepted |
| `DISABLE` | No mTLS enforcement |

If calling from outside the mesh and mode is `STRICT`, temporarily set to `PERMISSIVE`:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: <namespace>
spec:
  mtls:
    mode: PERMISSIVE
```

---

## 6. Verify VirtualService / DestinationRule

```bash
kubectl get virtualservice -n <namespace>
kubectl get destinationrule -n <namespace>
```

- **If none exist:** Envoy uses default Kubernetes service routing — this is fine.
- **If they exist:** Verify hosts, paths, and ports match your request.

```bash
kubectl get virtualservice <vs-name> -n <namespace> -o yaml
kubectl get destinationrule <dr-name> -n <namespace> -o yaml
```

---

## 7. End-to-End Test from Inside the Mesh

From another pod with a sidecar:

```bash
kubectl exec <other-pod> -n <namespace> -c istio-proxy -- curl -sv \
  http://<service-name>.<namespace>.svc.cluster.local:<port>/your/endpoint
```

### What to Look For in the Response

| Header / Status | Meaning |
|----------------|---------|
| `x-envoy-upstream-service-time: 12` | Request **reached** your app (value = ms) |
| `x-envoy-upstream-service-time` missing | Envoy **blocked** the request before forwarding |
| `server: istio-envoy` | Response came through Envoy (expected) |
| `200` | Success |
| `404` + upstream-service-time present | App returned 404 — check app routes |
| `404` + upstream-service-time missing | Envoy returned 404 — check Istio routing |
| `503` | Upstream unreachable — check app health and port |

---

## 8. Run Istio Analyze

```bash
istioctl analyze -n <namespace>
```

This automatically detects common misconfigurations such as:
- VirtualService referencing a non-existent Gateway
- DestinationRule with no matching Service
- Conflicting policies

---

## 9. Envoy Admin API (Direct Inspection)

If `istioctl proxy-config` has version mismatch issues, query Envoy directly:

```bash
# Dump full config to a local file
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- curl -s localhost:15000/config_dump > envoy_config.json

# Get routes only
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- curl -s localhost:15000/config_dump?resource=dynamic_route_configs > envoy_routes.json

# Get clusters (upstream services)
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- curl -s localhost:15000/clusters

# Get active listeners
kubectl exec <pod-name> -n <namespace> -c istio-proxy -- curl -s localhost:15000/listeners
```

Open the JSON files in VS Code and search for your service name or route path.

---

## Quick Decision Tree

```
Request sent
  │
  ├── No Envoy log at all?
  │     → Request isn't reaching the pod (check Service, Ingress, Gateway)
  │
  ├── Envoy log shows NR (No Route)?
  │     → Route mismatch — check VirtualService or host header
  │
  ├── Envoy log shows UH/UF?
  │     → App not running or wrong port — check Step 2
  │
  ├── Envoy log shows RBAC denied?
  │     → AuthorizationPolicy still exists — check Step 4
  │
  ├── Envoy log shows 200 but response is 404?
  │     → App returned 404 — check application routes
  │
  └── Envoy log shows 503?
        → Upstream unreachable — check app health, port, and mTLS mode
```

---

## Common Fixes Summary

| Problem | Fix |
|---------|-----|
| `NR` flag in logs | Add/fix VirtualService or use correct host |
| Endpoints `<none>` | Fix Service selector to match pod labels |
| RBAC denied | Delete residual AuthorizationPolicy (check `istio-system` too) |
| mTLS rejection | Set PeerAuthentication to `PERMISSIVE` |
| App 404 | Fix application route mapping (`@RequestMapping`, context path) |
| `istioctl` version mismatch | Use Envoy admin API on port 15000 directly |
