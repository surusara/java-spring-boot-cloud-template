package com.example.financialstream.shutdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class KafkaStreamsShutdownHook {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsShutdownHook.class);
    private static final long SHUTDOWN_GRACE_PERIOD_MS = 60_000;  // 60 seconds

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    public KafkaStreamsShutdownHook(
            @Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
        Runtime.getRuntime().addShutdownHook(new Thread(this::gracefulShutdown, "kafka-shutdown-hook"));
    }

    private void gracefulShutdown() {
        log.info("Initiating graceful Kafka Streams shutdown...");

        try {
            // Stop accepting new records and commit offsets
            streamsBuilderFactoryBean.stop();
            
            // Give some time for graceful shutdown
            Thread.sleep(SHUTDOWN_GRACE_PERIOD_MS);
            
            log.info("✓ Graceful shutdown complete");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("⚠ Shutdown interrupted");
        } catch (Exception ex) {
            log.warn("⚠ Error during graceful shutdown: {}", ex.getMessage());
        }
    }
}
