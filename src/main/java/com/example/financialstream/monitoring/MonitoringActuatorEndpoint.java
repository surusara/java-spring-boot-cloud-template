package com.example.financialstream.monitoring;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Custom Actuator endpoint exposing the full monitoring dashboard on the management port.
 *
 * <p>Access: {@code GET /actuator/monitoring} on management port 9090.
 *
 * <p>Activate by adding {@code monitoring} to
 * {@code management.endpoints.web.exposure.include} in your configuration.
 */
@Component
@Endpoint(id = "monitoring")
public class MonitoringActuatorEndpoint {

    private final MonitoringDashboardService dashboardService;

    public MonitoringActuatorEndpoint(MonitoringDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @ReadOperation
    public Map<String, Object> monitoring() {
        return dashboardService.getDashboard();
    }
}
