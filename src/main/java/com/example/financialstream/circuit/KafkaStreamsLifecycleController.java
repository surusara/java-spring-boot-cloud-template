package com.example.financialstream.circuit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-grade lifecycle controller for Kafka Streams.
 * Manages safe start/stop of the stream with proper state tracking.
 *
 * <p><b>Why a dedicated executor?</b> Resilience4j fires its {@code onStateTransition} listener
 * <i>synchronously on the thread that crossed the failure threshold</i> — and that thread is a
 * Kafka <b>StreamThread</b> (the OPEN transition happens inside {@code circuitBreaker.onError(...)},
 * which runs inside the processor's {@code record(...)} call). Calling
 * {@code StreamsBuilderFactoryBean.stop()} directly invokes {@code KafkaStreams.close()}, which
 * blocks until <i>all</i> StreamThreads terminate — including the calling one. A thread cannot
 * join itself, so the call would block for the full close timeout and shut down uncleanly.
 *
 * <p>To stay 100% safe, all {@code stop()}/{@code start()} work is offloaded to a dedicated
 * single-thread executor. The breaker-intent flag is flipped <i>synchronously</i> (so
 * {@link #isStoppedByBreaker()} is immediately accurate), while the blocking lifecycle call runs
 * off the StreamThread. The current StreamThread returns immediately, finishes/commits its batch,
 * then the executor closes the instance cleanly.
 */
@Component
public class KafkaStreamsLifecycleController implements StreamLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsLifecycleController.class);

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final AtomicBoolean stoppedByBreaker = new AtomicBoolean(false);

    /** Single-thread executor: serializes stop/start and keeps the blocking close() off any StreamThread. */
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cb-stream-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    public KafkaStreamsLifecycleController(@Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public void stopStream() {
        // Flip intent synchronously so isStoppedByBreaker() is correct immediately,
        // but run the blocking close() off the StreamThread to avoid a self-join deadlock.
        if (stoppedByBreaker.compareAndSet(false, true)) {
            lifecycleExecutor.submit(() -> {
                try {
                    log.warn("⏸️  Stopping Kafka Streams - circuit breaker OPEN (consumer goes offline, no fetching, no buffering)");
                    streamsBuilderFactoryBean.stop();
                    log.info("✓ Streams stopped successfully");
                } catch (Exception ex) {
                    // Revert intent so a later recovery attempt can retry; never propagate from the executor thread.
                    log.error("Error stopping stream — reverting breaker-stop state so it can be retried", ex);
                    stoppedByBreaker.set(false);
                }
            });
        } else {
            log.debug("Stream already stopped by breaker — ignoring duplicate stop request");
        }
    }

    @Override
    public void startStream() {
        if (stoppedByBreaker.compareAndSet(true, false)) {
            lifecycleExecutor.submit(() -> {
                try {
                    log.info("▶️  Starting Kafka Streams - recovery in progress");
                    streamsBuilderFactoryBean.start();
                    log.info("✓ Streams started successfully");
                } catch (Exception ex) {
                    // Revert intent so the recovery scheduler can retry the restart.
                    log.error("Error starting stream — reverting state so recovery can retry", ex);
                    stoppedByBreaker.set(true);
                }
            });
        } else {
            log.debug("Stream already running — ignoring duplicate start request");
        }
    }

    @Override
    public boolean isStoppedByBreaker() {
        return stoppedByBreaker.get();
    }

    @PreDestroy
    void shutdown() {
        lifecycleExecutor.shutdown();
        try {
            if (!lifecycleExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                lifecycleExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            lifecycleExecutor.shutdownNow();
        }
    }
}
