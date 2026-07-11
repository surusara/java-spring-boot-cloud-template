package com.example.consumer.outbox;

import com.example.consumer.avro.PaymentApproved;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AvroOutboxCodecTest {

    private final AvroOutboxCodec codec = new AvroOutboxCodec();

    @Test
    void encodeThenDecodeRoundTripsAllFields() {
        PaymentApproved original = PaymentApproved.newBuilder()
                .setEventId("p1::APPROVED")
                .setPaymentId("p1")
                .setAccountId("acc-9")
                .setAmount(new BigDecimal("123.45"))
                .setCurrency("USD")
                .setRiskTier("LOW")
                .setApprovedAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .build();

        byte[] bytes = codec.encode(original);
        PaymentApproved decoded = codec.decode(bytes, PaymentApproved.class);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.getAmount()).isEqualByComparingTo("123.45");
        assertThat(decoded.getRiskTier()).isEqualTo("LOW");
    }
}
