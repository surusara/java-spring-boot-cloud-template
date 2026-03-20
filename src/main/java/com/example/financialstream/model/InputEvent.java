package com.example.financialstream.model;

public record InputEvent(
        String eventId,
        String correlationId,
        String cid,
        String eventType,
        String payload,
        boolean softBusinessFailure,
        boolean fatalBusinessFailure
) {
}
