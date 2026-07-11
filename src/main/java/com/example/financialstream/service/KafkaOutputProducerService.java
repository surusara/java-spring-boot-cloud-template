package com.example.financialstream.service;

import com.example.financialstream.model.OutputEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transactional Kafka producer service.
 *
 * <p>Each {@link #send(OutputEvent)} call executes inside its own Kafka transaction
 * (init/begin/commit/abort) via {@link KafkaTemplate#executeInTransaction}.
 * Downstream consumers MUST use {@code isolation.level=read_committed} to only see
 * committed records.
 *
 * <p><b>Not for use from the Kafka Streams processor.</b> Forward downstream records
 * via {@code ProcessorContext.forward(...)} so they share Streams' transaction.
 * See {@link OutputProducerService} javadoc for the duplicate-on-rebalance failure
 * mode this avoids.
 */
@Service
@SuppressWarnings("deprecation") // implements deprecated interface intentionally; out-of-stream callers may still use it
public class KafkaOutputProducerService implements OutputProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutputProducerService.class);

    private final KafkaTemplate<String, OutputEvent> kafkaTemplate;
    private final String outputTopic;

    public KafkaOutputProducerService(KafkaTemplate<String, OutputEvent> kafkaTemplate,
                                      @Value("${app.output.topic:payments.output}") String outputTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.outputTopic = outputTopic;
    }

    @Override
    public void send(OutputEvent event) {
        try {
            // executeInTransaction wraps the send in a Kafka transaction:
            //   - Calls initTransactions() (once per producer lifecycle)
            //   - beginTransaction() before the lambda
            //   - commitTransaction() if lambda succeeds
            //   - abortTransaction() if lambda throws
            // The .get(30, SECONDS) blocks until the broker acknowledges the record,
            // ensuring we know the write succeeded before committing the transaction.
            kafkaTemplate.executeInTransaction(ops -> {
                try {
                    return ops.send(outputTopic, event.eventId(), event).get(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    // Restore the interrupt flag so the caller/thread pool can observe the interruption.
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while awaiting broker ack for send", ex);
                } catch (ExecutionException | TimeoutException ex) {
                    throw new RuntimeException("Send within transaction failed", ex);
                }
            });
            log.info("Published output event {} to {} (committed)", event.eventId(), outputTopic);
        } catch (Exception ex) {
            log.error("Transactional send failed for event {} to {}", event.eventId(), outputTopic, ex);
            throw new IllegalStateException("Transactional output producer failed for event " + event.eventId(), ex);
        }
    }
}
