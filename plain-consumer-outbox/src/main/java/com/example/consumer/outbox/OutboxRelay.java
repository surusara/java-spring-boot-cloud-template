package com.example.consumer.outbox;

import com.example.consumer.avro.PaymentApproved;
import com.example.consumer.avro.PaymentAudit;
import com.example.consumer.config.AppProperties;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains PENDING outbox rows and publishes them to Kafka (Avro via Schema Registry), then flips
 * them to SENT — all inside one transaction so the SKIP-LOCKED row locks are held for the cycle.
 *
 * <p>Delivery is <b>at-least-once</b>: if the pod dies after the broker ack but before the DB
 * commit, the row stays PENDING and is re-published on restart. Downstream consumers must dedupe on
 * {@code eventId} (which is deterministic: {@code <paymentId>::APPROVED} / {@code ::AUDIT}).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AvroOutboxCodec codec;
    private final AppProperties props;

    public OutboxRelay(OutboxRepository outboxRepo,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       AvroOutboxCodec codec,
                       AppProperties props) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepo.claimPendingBatch(props.getOutbox().getBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        int sent = 0;
        for (OutboxEvent event : batch) {
            event.recordAttempt();
            try {
                SpecificRecord record = decode(event);
                // Synchronous: block for the broker ack before marking SENT so we never mark a row
                // SENT that wasn't actually accepted.
                kafkaTemplate.send(event.getTopic(), event.getEventKey(), record)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markSent();
                sent++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted publishing outbox event {}; will retry next cycle", event.getEventId());
                break;
            } catch (Exception ex) {
                // Leave this row PENDING and stop the batch to preserve per-key ordering; retried next cycle.
                log.warn("Failed to publish outbox event {} (attempt {}): {}",
                        event.getEventId(), event.getAttempts(), ex.getMessage());
                break;
            }
        }
        if (sent > 0) {
            log.debug("Relay published {}/{} outbox events", sent, batch.size());
        }
    }

    private SpecificRecord decode(OutboxEvent event) {
        return switch (event.getEventType()) {
            case PAYMENT_APPROVED -> codec.decode(event.getPayload(), PaymentApproved.class);
            case PAYMENT_AUDIT -> codec.decode(event.getPayload(), PaymentAudit.class);
        };
    }
}
