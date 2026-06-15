package com.example.financialstream.model;

public record ProcessingResult(
        ProcessingStatus status,
        String code,
        String message,
        // Optional downstream record. When non-null, the processor forwards it through
        // Kafka Streams' own producer so the input-offset commit and the output write
        // share the same transaction under processing.guarantee=exactly_once_v2.
        OutputEvent output
) {
    public static ProcessingResult success(String code) {
        return new ProcessingResult(ProcessingStatus.SUCCESS, code, null, null);
    }

    public static ProcessingResult success(String code, OutputEvent output) {
        return new ProcessingResult(ProcessingStatus.SUCCESS, code, null, output);
    }

    public static ProcessingResult successWithExceptionLogged(String code, String message) {
        return new ProcessingResult(ProcessingStatus.SUCCESS_WITH_EXCEPTION_LOGGED, code, message, null);
    }
}
