package com.example.financialstream.circuit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-grade lifecycle controller for Kafka Streams.
 * Manages safe start/stop of stream with proper state tracking.
 */
@Component
public class KafkaStreamsLifecycleController implements StreamLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsLifecycleController.class);

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final AtomicBoolean stoppedByBreaker = new AtomicBoolean(false);

    public KafkaStreamsLifecycleController(@Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public synchronized void stopStream() {
        if (stoppedByBreaker.compareAndSet(false, true)) {
            try {
                log.warn("⏸️  Stopping Kafka Streams - Circuit breaker triggered");
                streamsBuilderFactoryBean.stop();
                log.info("✓ Streams stopped successfully");
            } catch (Exception ex) {
                log.error("Error stopping stream", ex);
                stoppedByBreaker.set(false);  // Reset state on error
                throw new IllegalStateException("Failed to stop stream", ex);
            }
        } else {
            log.debug("Stream already stopped by breaker");
        }
    }

    @Override
    public synchronized void startStream() {
        if (stoppedByBreaker.compareAndSet(true, false)) {
            try {
                log.info("▶️  Starting Kafka Streams - Recovery in progress");
                streamsBuilderFactoryBean.start();
                log.info("✓ Streams started successfully");
            } catch (Exception ex) {
                log.error("Error starting stream", ex);
                stoppedByBreaker.set(true);  // Reset state on error
                throw new IllegalStateException("Failed to start stream", ex);
            }
        } else {
            log.debug("Stream already running");
        }
    }

    @Override
    public boolean isStoppedByBreaker() {
        return stoppedByBreaker.get();
    }
}
