package com.example.financialstream.kafka;

import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Production-grade uncaught exception handler for Kafka Streams.
 *
 * Strategy:
 *   REPLACE_THREAD  — for transient/recoverable errors (network, timeout, broker disconnect).
 *                     Kafka Streams kills the failed thread and spins up a replacement.
 *                     Partitions are reassigned within the same instance — no full rebalance.
 *
 *   SHUTDOWN_CLIENT — for fatal/unrecoverable errors (bugs, OOM, data corruption).
 *                     Publishes LivenessState.BROKEN → liveness probe returns 503 →
 *                     kubelet restarts the pod after failureThreshold consecutive failures.
 *
 * Why publish LivenessState.BROKEN explicitly?
 *   SHUTDOWN_CLIENT stops all stream threads but the JVM stays alive.
 *   Spring Boot's /actuator/health/liveness only auto-detects BROKEN if the
 *   KafkaStreams state change event propagates through Spring's availability system.
 *   Not all Spring Kafka versions guarantee this. Publishing explicitly eliminates
 *   the risk of a zombie pod (JVM alive, liveness UP, but no stream processing).
 *
 * Why not always SHUTDOWN_CLIENT?
 *   Each pod restart triggers a consumer group rebalance across all 48 partitions.
 *   With static membership (group.instance.id), the rebalance is delayed by session.timeout.ms
 *   but still causes processing gaps. REPLACE_THREAD avoids this for transient issues.
 *
 * Why not always REPLACE_THREAD?
 *   A genuine bug will keep crashing threads in a tight loop → CPU spin + log flood.
 *   Fatal errors need a full restart to reload state and get a clean JVM.
 */
@Component
public class StreamFatalExceptionHandler implements StreamsUncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamFatalExceptionHandler.class);

    private final ApplicationEventPublisher eventPublisher;

    public StreamFatalExceptionHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Exception types that are safe to recover from by replacing the stream thread.
     * All Kafka RetriableException subclasses are also treated as recoverable (checked via instanceof).
     */
    private static final Set<Class<? extends Throwable>> RECOVERABLE_TYPES = Set.of(
            TimeoutException.class,
            DisconnectException.class
    );

    @Override
    public StreamThreadExceptionResponse handle(Throwable exception) {
        Throwable root = getRootCause(exception);

        if (isRecoverable(root)) {
            log.warn("⚠️ Recoverable exception in Kafka Streams — replacing thread. Root cause: {}",
                    root.getMessage(), exception);
            return StreamThreadExceptionResponse.REPLACE_THREAD;
        }

        log.error("❌ Fatal exception in Kafka Streams — triggering SHUTDOWN_CLIENT for pod restart. "
                + "Root cause: {}", root.getMessage(), exception);

        // Signal Spring Boot that the application is BROKEN.
        // /actuator/health/liveness will return 503 → kubelet restarts the pod.
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
        log.info("Published LivenessState.BROKEN — liveness probe will fail, pod restart imminent");

        return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
    }

    private boolean isRecoverable(Throwable root) {
        // Kafka's own retriable exceptions (NetworkException, NotLeaderOrFollowerException, etc.)
        if (root instanceof RetriableException) {
            return true;
        }
        // Explicit recoverable types (java.util.concurrent.TimeoutException, etc.)
        return RECOVERABLE_TYPES.contains(root.getClass());
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
