package com.example.consumer.service;

import com.example.consumer.avro.PaymentApproved;
import com.example.consumer.avro.PaymentAudit;
import com.example.consumer.avro.PaymentInput;
import com.example.consumer.config.AppProperties;
import com.example.consumer.dedupe.ProcessedMessage;
import com.example.consumer.dedupe.ProcessedMessageRepository;
import com.example.consumer.domain.Payment;
import com.example.consumer.domain.PaymentRepository;
import com.example.consumer.outbox.AvroOutboxCodec;
import com.example.consumer.outbox.OutboxEvent;
import com.example.consumer.outbox.OutboxEventType;
import com.example.consumer.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies one payment atomically: the dedupe row, the business row, and the two outbox rows all
 * commit (or roll back) together in a single DB transaction. Publishing to Kafka is deferred to the
 * outbox relay, so there is no dual-write between the DB and Kafka.
 */
@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final ProcessedMessageRepository processedRepo;
    private final PaymentRepository paymentRepo;
    private final OutboxRepository outboxRepo;
    private final EnrichmentClient enrichmentClient;
    private final AvroOutboxCodec codec;
    private final AppProperties props;

    public PaymentProcessingService(ProcessedMessageRepository processedRepo,
                                    PaymentRepository paymentRepo,
                                    OutboxRepository outboxRepo,
                                    EnrichmentClient enrichmentClient,
                                    AvroOutboxCodec codec,
                                    AppProperties props) {
        this.processedRepo = processedRepo;
        this.paymentRepo = paymentRepo;
        this.outboxRepo = outboxRepo;
        this.enrichmentClient = enrichmentClient;
        this.codec = codec;
        this.props = props;
    }

    @Transactional
    public void process(PaymentInput input) {
        String paymentId = input.getPaymentId();

        // Idempotency: a replayed record (offset re-read after a rebalance) is a no-op.
        if (processedRepo.existsById(paymentId)) {
            log.debug("Skipping already-processed payment {}", paymentId);
            return;
        }

        // Enrichment via the circuit-breaker-guarded dependency. If the breaker is OPEN this throws
        // CallNotPermittedException, the transaction rolls back, and the record is re-delivered later.
        String riskTier = enrichmentClient.lookupRiskTier(input.getAccountId());

        Instant now = Instant.now();
        paymentRepo.save(new Payment(
                paymentId,
                input.getAccountId(),
                input.getAmount(),
                input.getCurrency(),
                riskTier,
                "APPROVED",
                input.getCreatedAt()));

        PaymentApproved approved = PaymentApproved.newBuilder()
                .setEventId(paymentId + "::APPROVED")
                .setPaymentId(paymentId)
                .setAccountId(input.getAccountId())
                .setAmount(input.getAmount())
                .setCurrency(input.getCurrency())
                .setRiskTier(riskTier)
                .setApprovedAt(now)
                .build();
        outboxRepo.save(new OutboxEvent(
                approved.getEventId(),
                OutboxEventType.PAYMENT_APPROVED,
                props.getOutbox().getApprovedTopic(),
                paymentId,
                codec.encode(approved)));

        PaymentAudit audit = PaymentAudit.newBuilder()
                .setEventId(paymentId + "::AUDIT")
                .setPaymentId(paymentId)
                .setOutcome("APPROVED")
                .setDetail("riskTier=" + riskTier)
                .setRecordedAt(now)
                .build();
        outboxRepo.save(new OutboxEvent(
                audit.getEventId(),
                OutboxEventType.PAYMENT_AUDIT,
                props.getOutbox().getAuditTopic(),
                paymentId,
                codec.encode(audit)));

        processedRepo.save(new ProcessedMessage(paymentId, now));
        log.info("Processed payment {} (riskTier={}) → 2 outbox events queued", paymentId, riskTier);
    }
}
