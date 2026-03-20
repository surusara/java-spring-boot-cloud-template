package com.example.financialstream.service;

import com.example.financialstream.model.OutputEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Transactional Kafka producer service.
 *
 * Each send() call executes inside a Kafka transaction:
 *   1. beginTransaction()
 *   2. send() the output record
 *   3. commitTransaction() on success / abortTransaction() on failure
 *
 * Downstream consumers MUST use isolation.level=read_committed to only see committed records.
 */
@Service
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
                } catch (Exception ex) {
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
