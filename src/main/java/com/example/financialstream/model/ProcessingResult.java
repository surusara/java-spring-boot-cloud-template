package com.example.financialstream.model;

public record ProcessingResult(
        ProcessingStatus status,
        String code,
        String message
) {
    public static ProcessingResult success(String code) {
        return new ProcessingResult(ProcessingStatus.SUCCESS, code, null);
    }

    public static ProcessingResult successWithExceptionLogged(String code, String message) {
        return new ProcessingResult(ProcessingStatus.SUCCESS_WITH_EXCEPTION_LOGGED, code, message);
    }
}
