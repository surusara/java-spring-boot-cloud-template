package com.example.consumer.circuit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Pauses/resumes all Kafka listener containers.
 *
 * <p>Unlike stopping a Kafka Streams client, {@code Consumer.pause()} keeps the consumer <b>in the
 * group and heartbeating</b> — it simply stops fetching for the assigned partitions. So during a
 * dependency outage there is no rebalance, no partition hand-off, and no need for a long
 * {@code session.timeout.ms}: the same pod keeps its partitions and resumes exactly where it left
 * off once the breaker recovers.
 */
@Component
public class ConsumerPauseController {

    private static final Logger log = LoggerFactory.getLogger(ConsumerPauseController.class);

    private final KafkaListenerEndpointRegistry registry;

    public ConsumerPauseController(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    public synchronized void pauseAll() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isContainerPaused() && container.isRunning()) {
                container.pause();
                log.warn("⏸️  Paused listener container [{}] — dependency unavailable (breaker OPEN)",
                        container.getListenerId());
            }
        }
    }

    public synchronized void resumeAll() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isContainerPaused()) {
                container.resume();
                log.info("▶️  Resumed listener container [{}] — dependency recovered", container.getListenerId());
            }
        }
    }

    public synchronized boolean isAnyPaused() {
        return registry.getListenerContainers().stream()
                .anyMatch(MessageListenerContainer::isContainerPaused);
    }
}
