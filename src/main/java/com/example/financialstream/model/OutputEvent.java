package com.example.financialstream.model;

import java.time.Instant;

public record OutputEvent(
        String eventId,
        String correlationId,
        String outcome,
        Instant processedAt
) {
}
