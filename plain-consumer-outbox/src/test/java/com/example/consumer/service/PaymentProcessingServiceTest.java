package com.example.consumer.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceTest {

    @Mock private ProcessedMessageRepository processedRepo;
    @Mock private PaymentRepository paymentRepo;
    @Mock private OutboxRepository outboxRepo;
    @Mock private EnrichmentClient enrichmentClient;
    @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

    private PaymentProcessingService service;

    @BeforeEach
    void setUp() {
        service = new PaymentProcessingService(processedRepo, paymentRepo, outboxRepo,
                enrichmentClient, new AvroOutboxCodec(), new AppProperties());
    }

    private PaymentInput input() {
        return PaymentInput.newBuilder()
                .setPaymentId("p1")
                .setAccountId("acc-1")
                .setAmount(new BigDecimal("10.00"))
                .setCurrency("USD")
                .setCreatedAt(Instant.now())
                .build();
    }

    @Test
    void processPersistsPaymentAndTwoOutboxRowsAtomically() {
        when(processedRepo.existsById("p1")).thenReturn(false);
        when(enrichmentClient.lookupRiskTier("acc-1")).thenReturn("LOW");

        service.process(input());

        verify(paymentRepo).save(org.mockito.ArgumentMatchers.any(Payment.class));
        verify(outboxRepo, times(2)).save(outboxCaptor.capture());
        verify(processedRepo).save(org.mockito.ArgumentMatchers.any(ProcessedMessage.class));

        List<OutboxEvent> events = outboxCaptor.getAllValues();
        assertThat(events).extracting(OutboxEvent::getEventType)
                .containsExactly(OutboxEventType.PAYMENT_APPROVED, OutboxEventType.PAYMENT_AUDIT);
        assertThat(events).extracting(OutboxEvent::getEventId)
                .containsExactly("p1::APPROVED", "p1::AUDIT");
    }

    @Test
    void alreadyProcessedRecordIsSkipped() {
        when(processedRepo.existsById("p1")).thenReturn(true);

        service.process(input());

        verify(enrichmentClient, never()).lookupRiskTier(org.mockito.ArgumentMatchers.anyString());
        verify(paymentRepo, never()).save(org.mockito.ArgumentMatchers.any());
        verify(outboxRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
