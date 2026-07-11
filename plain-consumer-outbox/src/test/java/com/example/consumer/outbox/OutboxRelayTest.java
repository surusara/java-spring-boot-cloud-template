package com.example.consumer.outbox;

import com.example.consumer.avro.PaymentApproved;
import com.example.consumer.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxRepository outboxRepo;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private OutboxRelay relay;
    private final AvroOutboxCodec codec = new AvroOutboxCodec();

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepo, kafkaTemplate, codec, new AppProperties());
    }

    private OutboxEvent approvedEvent(String key) {
        PaymentApproved rec = PaymentApproved.newBuilder()
                .setEventId(key + "::APPROVED").setPaymentId(key).setAccountId("a")
                .setAmount(new BigDecimal("1.00")).setCurrency("USD").setRiskTier("LOW")
                .setApprovedAt(Instant.now()).build();
        return new OutboxEvent(rec.getEventId(), OutboxEventType.PAYMENT_APPROVED,
                "payments.approved", key, codec.encode(rec));
    }

    @Test
    void publishesPendingRowsAndMarksThemSent() {
        OutboxEvent e1 = approvedEvent("p1");
        OutboxEvent e2 = approvedEvent("p2");
        when(outboxRepo.claimPendingBatch(anyInt())).thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(eq("payments.approved"), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        relay.publishPending();

        verify(kafkaTemplate, times(2)).send(eq("payments.approved"), anyString(), any());
        assertThat(e1.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(e2.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void failedSendLeavesRowPendingAndStopsBatch() {
        OutboxEvent e1 = approvedEvent("p1");
        OutboxEvent e2 = approvedEvent("p2");
        when(outboxRepo.claimPendingBatch(anyInt())).thenReturn(List.of(e1, e2));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(eq("payments.approved"), anyString(), any()))
                .thenAnswer(inv -> failed);

        relay.publishPending();

        assertThat(e1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e1.getAttempts()).isEqualTo(1);
        // batch stopped after the first failure to preserve ordering
        assertThat(e2.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e2.getAttempts()).isZero();
    }
}
