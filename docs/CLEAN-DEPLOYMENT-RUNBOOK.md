# Clean Deployment Runbook

> **Purpose:** Step-by-step process to fully clean an old Helm release, ConfigMap, pods, replicas, and do a complete fresh deployment.  
> **When to use:** Pipeline deployment is not working, ConfigMap or code is stuck on a very old version, or you need a guaranteed clean slate.

---

## Prerequisites

- `kubectl` configured to your AKS cluster
- `helm` CLI installed
- Access to your container registry (ACR)
- Namespace known (replace `<namespace>` below)

---

## Step 1: Inspect Current State

```bash
# See the old Helm release
helm list -n <namespace> -a

# See all K8s resources from the release
helm get manifest <release-name> -n <namespace> | grep "kind:"

# See running pods
kubectl get pods -n <namespace> -l app=<app-label>

# See current ConfigMap values
kubectl get configmap <configmap-name> -n <namespace> -o yaml

# See current image version
kubectl describe pod <pod-name> -n <namespace> | grep "Image:"
```

---

## Step 2: Delete Old Helm Release

```bash
# Removes Deployment, ConfigMap, Service, etc. managed by Helm
helm uninstall <release-name> -n <namespace>
```

If the release is stuck in a failed/pending state:
```bash
helm uninstall <release-name> -n <namespace> --no-hooks
```

---

## Step 3: Verify Everything Is Gone

```bash
# Check for orphaned resources
kubectl get all -n <namespace> -l app=<app-label>
kubectl get configmap -n <namespace> -l app=<app-label>
kubectl get secret -n <namespace> -l owner=helm

# Delete orphaned resources manually if any remain
kubectl delete configmap <configmap-name> -n <namespace>
kubectl delete pod <pod-name> -n <namespace> --grace-period=0 --force
kubectl delete replicaset -l app=<app-label> -n <namespace>
```

---

## Step 4: Clean Up KEDA ScaledObject (If Applicable)

```bash
# Check if ScaledObject still references the old deployment
kubectl get scaledobject -n <namespace>
kubectl delete scaledobject <scaler-name> -n <namespace>

# Remove orphaned HPA created by KEDA
kubectl get hpa -n <namespace>
kubectl delete hpa <hpa-name> -n <namespace>
```

---

## Step 5: Deploy Fresh

### Option A: From Artifactory (Requires Build Uploaded)

```bash
# Update Helm repo cache
helm repo update

# Install fresh from Artifactory
helm install <release-name> <artifactory-repo>/<chart-name> \
  --version <chart-version> \
  -f values.yaml \
  -n <namespace> \
  --wait --timeout 5m
```

### Option B: From Local Chart (No Need to Wait for Pipeline)

```bash
# Build and push Docker image manually
docker build -t <your-registry>/<image-name>:<tag> .
docker push <your-registry>/<image-name>:<tag>

# Install from local chart directory
helm install <release-name> ./path-to-chart/ \
  -f values.yaml \
  -n <namespace> \
  --wait --timeout 5m
```

> **Tip:** Update `image.tag` in `values.yaml` to match the tag you just pushed.

---

## Step 6: Verify New Deployment

```bash
# Pods running with new image
kubectl get pods -n <namespace> -l app=<app-label>
kubectl describe pod <pod-name> -n <namespace> | grep "Image:"

# ConfigMap has new values
kubectl get configmap <configmap-name> -n <namespace> -o yaml

# Helm release is clean
helm list -n <namespace>
helm get values <release-name> -n <namespace>

# Check logs
kubectl logs <pod-name> -n <namespace> --tail=50

# Health check
kubectl port-forward <pod-name> 9090:9090 -n <namespace>
# Then: curl http://localhost:9090/actuator/health
```

---

## Step 7: Re-apply KEDA ScaledObject (If Not Part of Helm Chart)

```bash
kubectl apply -f keda-scaledobject.yaml -n <namespace>
kubectl get scaledobject -n <namespace>
kubectl get hpa -n <namespace>
```

---

## Quick Reference — Copy-Paste One Shot

```bash
# ── Clean everything ──
helm uninstall <release-name> -n <namespace>
kubectl delete all -l app=<app-label> -n <namespace>
kubectl delete configmap -l app=<app-label> -n <namespace>
kubectl delete scaledobject <scaler-name> -n <namespace>

# ── Fresh install ──
helm install <release-name> ./chart/ \
  -f values.yaml \
  -n <namespace> \
  --wait --timeout 5m
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `helm uninstall` hangs | Finalizers on resources | `kubectl patch <resource> -p '{"metadata":{"finalizers":null}}' -n <namespace>` |
| ConfigMap still shows old values | Orphaned (created outside Helm) | `kubectl delete configmap <name> -n <namespace>` |
| Pod still running after uninstall | ReplicaSet not garbage-collected | `kubectl delete replicaset -l app=<app-label> -n <namespace>` |
| Helm secret leftovers | Release metadata stuck | `kubectl delete secret sh.helm.release.v1.<release-name>.v<revision> -n <namespace>` |
| New pod CrashLoopBackOff | Wrong image tag or missing ConfigMap | Check `kubectl describe pod` and `kubectl logs` |
| KEDA not scaling | ScaledObject deleted in Step 4 | Re-apply in Step 7 |

---

## Key Reminder

You do **not** need to wait for the pipeline if you can:
1. `docker build` + `docker push` the image locally to your container registry
2. Have the Helm chart files available locally

The pipeline is just automation around these same steps.
