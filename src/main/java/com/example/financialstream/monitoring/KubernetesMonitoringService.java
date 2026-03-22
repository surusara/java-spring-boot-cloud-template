package com.example.financialstream.monitoring;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.AutoscalingV2Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V2HorizontalPodAutoscaler;
import io.kubernetes.client.openapi.models.V2HorizontalPodAutoscalerCondition;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries the Kubernetes API from inside the pod to retrieve pod counts,
 * deployment replica counts, HPA status, and KEDA ScaledObject state.
 *
 * <p>Auto-detects in-cluster config (ServiceAccount token at
 * {@code /var/run/secrets/kubernetes.io/serviceaccount/token}).
 * When {@code app.monitoring.k8s-enabled=false} (default for local dev),
 * all methods return empty/mock data with an informational note — no failures.
 *
 * <p>Requires RBAC: Role {@code payments-stream-monitoring-reader} bound to the
 * {@code payments-stream} ServiceAccount. See {@code k8s-rbac-monitoring.yaml}.
 */
@Service
public class KubernetesMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesMonitoringService.class);

    /** Prefix/suffix for the KEDA-managed HPA name: "keda-hpa-{deploymentName}-scaler". */
    private static final String KEDA_HPA_PREFIX = "keda-hpa-";
    private static final String KEDA_HPA_SUFFIX = "-scaler";

    private final MonitoringProperties props;

    public KubernetesMonitoringService(MonitoringProperties props) {
        this.props = props;
    }

    /**
     * Returns pod list with name, status, ready, age, node, and IP.
     */
    public Map<String, Object> getPodInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!props.isK8sEnabled()) {
            return localDevPlaceholder("pods");
        }
        try {
            CoreV1Api api = buildCoreV1Api();
            V1PodList podList = api.listNamespacedPod(props.getNamespace())
                    .labelSelector(props.getPodLabelSelector())
                    .execute();

            List<Map<String, Object>> pods = new ArrayList<>();
            for (V1Pod pod : podList.getItems()) {
                pods.add(buildPodInfo(pod));
            }
            result.put("pods", pods);
            result.put("count", pods.size());
            result.put("labelSelector", props.getPodLabelSelector());
        } catch (Exception e) {
            log.warn("Could not fetch pods from Kubernetes API: {}", e.getMessage());
            result.put("error", "K8s API unavailable: " + e.getMessage());
            result.put("pods", List.of());
            result.put("count", 0);
        }
        return result;
    }

    /**
     * Returns deployment desired/current/ready replica counts.
     */
    public Map<String, Object> getDeploymentInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!props.isK8sEnabled()) {
            return localDevPlaceholder("deployment");
        }
        try {
            AppsV1Api api = buildAppsV1Api();
            V1Deployment deployment = api.readNamespacedDeployment(
                    props.getDeploymentName(), props.getNamespace()).execute();

            if (deployment.getSpec() != null) {
                result.put("desiredReplicas", deployment.getSpec().getReplicas());
            }
            if (deployment.getStatus() != null) {
                result.put("currentReplicas", deployment.getStatus().getReplicas());
                result.put("readyReplicas", deployment.getStatus().getReadyReplicas());
                result.put("updatedReplicas", deployment.getStatus().getUpdatedReplicas());
                result.put("availableReplicas", deployment.getStatus().getAvailableReplicas());
            }
            result.put("deploymentName", props.getDeploymentName());
        } catch (ApiException e) {
            log.warn("Could not fetch deployment '{}': HTTP {} - {}",
                    props.getDeploymentName(), e.getCode(), e.getMessage());
            result.put("error", "K8s API error " + e.getCode() + ": " + e.getMessage());
        } catch (Exception e) {
            log.warn("Could not fetch deployment info: {}", e.getMessage());
            result.put("error", "K8s API unavailable: " + e.getMessage());
        }
        return result;
    }

    /**
     * Returns HPA status: current/desired replicas, conditions, last scale time.
     * KEDA creates an HPA automatically for each ScaledObject.
     */
    public Map<String, Object> getHpaInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!props.isK8sEnabled()) {
            return localDevPlaceholder("hpa");
        }
        try {
            AutoscalingV2Api api = buildAutoscalingV2Api();
            // KEDA names the HPA "keda-hpa-<scaledObjectName>" by default.
            // The ScaledObject name follows the convention "<deploymentName>-scaler".
            // Override MonitoringProperties.deploymentName if your ScaledObject uses a different name.
            String hpaName = KEDA_HPA_PREFIX + props.getDeploymentName() + KEDA_HPA_SUFFIX;
            V2HorizontalPodAutoscaler hpa = api.readNamespacedHorizontalPodAutoscaler(
                    hpaName, props.getNamespace()).execute();

            if (hpa.getSpec() != null) {
                result.put("minReplicas", hpa.getSpec().getMinReplicas());
                result.put("maxReplicas", hpa.getSpec().getMaxReplicas());
            }
            if (hpa.getStatus() != null) {
                result.put("currentReplicas", hpa.getStatus().getCurrentReplicas());
                result.put("desiredReplicas", hpa.getStatus().getDesiredReplicas());
                result.put("lastScaleTime", formatTime(hpa.getStatus().getLastScaleTime()));

                List<Map<String, Object>> conditions = new ArrayList<>();
                if (hpa.getStatus().getConditions() != null) {
                    for (V2HorizontalPodAutoscalerCondition c : hpa.getStatus().getConditions()) {
                        conditions.add(Map.of(
                                "type", c.getType(),
                                "status", c.getStatus(),
                                "reason", c.getReason() != null ? c.getReason() : "",
                                "message", c.getMessage() != null ? c.getMessage() : ""
                        ));
                    }
                }
                result.put("conditions", conditions);
            }
            result.put("hpaName", hpaName);
        } catch (ApiException e) {
            log.warn("Could not fetch HPA: HTTP {} - {}", e.getCode(), e.getMessage());
            result.put("error", "K8s API error " + e.getCode() + ": " + e.getMessage());
        } catch (Exception e) {
            log.warn("Could not fetch HPA info: {}", e.getMessage());
            result.put("error", "K8s API unavailable: " + e.getMessage());
        }
        return result;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Map<String, Object> buildPodInfo(V1Pod pod) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (pod.getMetadata() != null) {
            info.put("name", pod.getMetadata().getName());
            info.put("age", formatAge(pod.getMetadata().getCreationTimestamp()));
        }
        if (pod.getSpec() != null) {
            info.put("nodeName", pod.getSpec().getNodeName());
        }
        if (pod.getStatus() != null) {
            info.put("phase", pod.getStatus().getPhase());
            info.put("podIp", pod.getStatus().getPodIP());
            boolean ready = false;
            if (pod.getStatus().getConditions() != null) {
                ready = pod.getStatus().getConditions().stream()
                        .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
            }
            info.put("ready", ready);
        }
        return info;
    }

    private String formatAge(OffsetDateTime creationTime) {
        if (creationTime == null) return "unknown";
        Duration age = Duration.between(creationTime.toInstant(), Instant.now());
        long hours = age.toHours();
        if (hours >= 24) return (hours / 24) + "d";
        if (hours >= 1) return hours + "h";
        return age.toMinutes() + "m";
    }

    private String formatTime(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toString();
    }

    private Map<String, Object> localDevPlaceholder(String section) {
        return Map.of(
                "note", "Kubernetes API disabled (app.monitoring.k8s-enabled=false). Set K8S_ENABLED=true in prod.",
                "section", section
        );
    }

    private CoreV1Api buildCoreV1Api() throws IOException {
        ApiClient client = Config.defaultClient();
        return new CoreV1Api(client);
    }

    private AppsV1Api buildAppsV1Api() throws IOException {
        ApiClient client = Config.defaultClient();
        return new AppsV1Api(client);
    }

    private AutoscalingV2Api buildAutoscalingV2Api() throws IOException {
        ApiClient client = Config.defaultClient();
        return new AutoscalingV2Api(client);
    }
}
