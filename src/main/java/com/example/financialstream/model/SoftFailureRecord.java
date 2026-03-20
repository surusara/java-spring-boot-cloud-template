package com.example.financialstream.model;

public record SoftFailureRecord(
        String streamName,
        String topic,
        int partition,
        long offset,
        String key,
        String correlationId,
        String code,
        String message,
        String payloadHash,
        String errorCategory,
        String dependencyName,
        String remediation
) {
    /**
     * Factory method to create a SoftFailureRecord from a SoftFailureReason
     */
    public static SoftFailureRecord of(
            String streamName, String topic, int partition, long offset,
            String key, String correlationId,
            SoftFailureReason reason, String payloadHash) {
        return new SoftFailureRecord(
            streamName, topic, partition, offset, key, correlationId,
            reason.code(), reason.message(),
            payloadHash,
            reason.category(), reason.dependencyName(), reason.remediation()
        );
    }

    /**
     * Legacy constructor for backward compatibility
     */
    public static SoftFailureRecord of(
            String streamName, String topic, int partition, long offset,
            String key, String correlationId,
            String code, String message, String payloadHash) {
        return new SoftFailureRecord(
            streamName, topic, partition, offset, key, correlationId,
            code, message, payloadHash,
            null, null, null
        );
    }
}
