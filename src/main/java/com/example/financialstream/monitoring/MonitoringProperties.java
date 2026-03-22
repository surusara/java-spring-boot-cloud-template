package com.example.financialstream.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for the monitoring dashboard.
 * All properties are under the {@code app.monitoring} prefix.
 */
@Component
@ConfigurationProperties(prefix = "app.monitoring")
public class MonitoringProperties {

    /** Enable or disable the monitoring dashboard entirely. */
    private boolean enabled = true;

    /** How often the dashboard cache refreshes (milliseconds). */
    private long refreshIntervalMs = 5000;

    /** SSE heartbeat interval (milliseconds). */
    private long sseHeartbeatMs = 5000;

    /** Kubernetes API call timeout (milliseconds). */
    private long k8sApiTimeoutMs = 3000;

    /** Kubernetes namespace — defaults to env var POD_NAMESPACE. */
    private String namespace = "financial-streams";

    /** Deployment name to watch for replica counts. */
    private String deploymentName = "payments-stream";

    /** Label selector to list pods. */
    private String podLabelSelector = "app=payments-stream";

    /** Consumer groups to monitor Kafka lag for. */
    private List<String> kafkaConsumerGroups = List.of("payments-stream-v1", "payments-exception-v1");

    /** Lag below this value is healthy (green). */
    private long lagThresholdWarning = 100;

    /** Lag at or above this value is critical (red). */
    private long lagThresholdCritical = 500;

    /**
     * Whether to make real Kubernetes API calls.
     * Set to false for local development — returns mock/empty data.
     */
    private boolean k8sEnabled = false;

    // ── Getters & setters ──────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }

    public long getSseHeartbeatMs() { return sseHeartbeatMs; }
    public void setSseHeartbeatMs(long sseHeartbeatMs) { this.sseHeartbeatMs = sseHeartbeatMs; }

    public long getK8sApiTimeoutMs() { return k8sApiTimeoutMs; }
    public void setK8sApiTimeoutMs(long k8sApiTimeoutMs) { this.k8sApiTimeoutMs = k8sApiTimeoutMs; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDeploymentName() { return deploymentName; }
    public void setDeploymentName(String deploymentName) { this.deploymentName = deploymentName; }

    public String getPodLabelSelector() { return podLabelSelector; }
    public void setPodLabelSelector(String podLabelSelector) { this.podLabelSelector = podLabelSelector; }

    public List<String> getKafkaConsumerGroups() { return kafkaConsumerGroups; }
    public void setKafkaConsumerGroups(List<String> kafkaConsumerGroups) { this.kafkaConsumerGroups = kafkaConsumerGroups; }

    public long getLagThresholdWarning() { return lagThresholdWarning; }
    public void setLagThresholdWarning(long lagThresholdWarning) { this.lagThresholdWarning = lagThresholdWarning; }

    public long getLagThresholdCritical() { return lagThresholdCritical; }
    public void setLagThresholdCritical(long lagThresholdCritical) { this.lagThresholdCritical = lagThresholdCritical; }

    public boolean isK8sEnabled() { return k8sEnabled; }
    public void setK8sEnabled(boolean k8sEnabled) { this.k8sEnabled = k8sEnabled; }
}
