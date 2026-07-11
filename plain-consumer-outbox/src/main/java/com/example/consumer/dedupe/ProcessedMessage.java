package com.example.consumer.dedupe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per business key already applied. Written inside the processing transaction so a
 * replayed record (offset re-read after a rebalance) is detected and skipped — this is what
 * turns the at-least-once listener into an effectively-once DB effect.
 */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public ProcessedMessage(String paymentId, Instant processedAt) {
        this.paymentId = paymentId;
        this.processedAt = processedAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
