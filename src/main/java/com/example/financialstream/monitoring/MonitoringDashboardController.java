package com.example.financialstream.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST and SSE controller for the monitoring dashboard.
 *
 * <p>Endpoints (business port 8080, context-path {@code /api/fs}):
 * <ul>
 *   <li>{@code GET /monitoring/dashboard} — full dashboard JSON</li>
 *   <li>{@code GET /monitoring/pods}        — pod scaling info only</li>
 *   <li>{@code GET /monitoring/kafka-lag}   — Kafka consumer lag only</li>
 *   <li>{@code GET /monitoring/keda}        — KEDA/HPA scaling status only</li>
 *   <li>{@code GET /monitoring/circuit-breaker} — circuit breaker state only</li>
 *   <li>{@code GET /monitoring/streams}     — Kafka Streams health only</li>
 *   <li>{@code GET /monitoring/sse}         — Server-Sent Events stream</li>
 * </ul>
 */
@RestController
@RequestMapping("/monitoring")
public class MonitoringDashboardController {

    private static final Logger log = LoggerFactory.getLogger(MonitoringDashboardController.class);

    private final MonitoringDashboardService dashboardService;
    private final MonitoringProperties props;

    /** Active SSE emitters — uses copy-on-write for thread-safe iteration. */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "monitoring-sse-scheduler");
                t.setDaemon(true);
                return t;
            });

    public MonitoringDashboardController(MonitoringDashboardService dashboardService,
                                          MonitoringProperties props) {
        this.dashboardService = dashboardService;
        this.props = props;
        startSseHeartbeat();
    }

    // ── REST endpoints ─────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }

    @GetMapping("/pods")
    public ResponseEntity<Map<String, Object>> pods() {
        return ResponseEntity.ok(dashboardService.getPods());
    }

    @GetMapping("/kafka-lag")
    public ResponseEntity<Map<String, Object>> kafkaLag() {
        return ResponseEntity.ok(dashboardService.getKafkaLag());
    }

    @GetMapping("/keda")
    public ResponseEntity<Map<String, Object>> keda() {
        return ResponseEntity.ok(dashboardService.getKeda());
    }

    @GetMapping("/circuit-breaker")
    public ResponseEntity<Map<String, Object>> circuitBreaker() {
        return ResponseEntity.ok(dashboardService.getCircuitBreaker());
    }

    @GetMapping("/streams")
    public ResponseEntity<Map<String, Object>> streams() {
        return ResponseEntity.ok(dashboardService.getStreams());
    }

    // ── SSE endpoint ───────────────────────────────────────────────────

    /**
     * Server-Sent Events endpoint for real-time dashboard push updates.
     * Clients connect once and receive dashboard snapshots at the configured interval.
     * Reconnection is handled automatically by the browser EventSource API.
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.debug("New SSE client connected. Active emitters: {}", emitters.size());
        // Send initial snapshot immediately
        sendSnapshot(emitter);
        return emitter;
    }

    // ── SSE heartbeat ──────────────────────────────────────────────────

    private void startSseHeartbeat() {
        long intervalMs = props.getSseHeartbeatMs();
        scheduler.scheduleAtFixedRate(this::broadcastToAllEmitters,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void broadcastToAllEmitters() {
        if (emitters.isEmpty()) return;
        Map<String, Object> snapshot = dashboardService.getDashboard();
        for (SseEmitter emitter : emitters) {
            sendSnapshot(emitter, snapshot);
        }
    }

    private void sendSnapshot(SseEmitter emitter) {
        sendSnapshot(emitter, dashboardService.getCachedDashboard());
    }

    private void sendSnapshot(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("dashboard").data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(emitter);
            log.debug("SSE client disconnected: {}", e.getMessage());
        }
    }
}
