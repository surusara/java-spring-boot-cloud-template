package com.example.financialstream.model;

public enum SoftFailureReason {
    ENRICHMENT_NOT_FOUND(
        "ENRICHMENT_NOT_FOUND",
        "Counterparty not found in enrichment DB",
        "ENRICHMENT",
        "enrichment-service",
        "Verify counterparty master data; check enrichment DB connectivity"
    ),
    VALIDATION_FAILED(
        "VALIDATION_FAILED",
        "Business rule validation failed",
        "VALIDATION",
        null,
        "Review validation rules; ensure input data format correct"
    ),
    EXTERNAL_API_TIMEOUT(
        "EXTERNAL_API_TIMEOUT",
        "External API did not respond in time",
        "EXTERNAL_SERVICE",
        "external-payment-gateway",
        "Increase timeout; check external service health"
    ),
    DATABASE_CONSTRAINT(
        "DATABASE_CONSTRAINT",
        "Database constraint violation (e.g., duplicate key)",
        "DATABASE",
        null,
        "Check for duplicate trade IDs; investigate business logic conflict"
    ),
    MISSING_REQUIRED_FIELD(
        "MISSING_REQUIRED_FIELD",
        "Required field is null or empty",
        "VALIDATION",
        null,
        "Verify source data completeness; check data format"
    ),
    INSUFFICIENT_BALANCE(
        "INSUFFICIENT_BALANCE",
        "Account has insufficient balance for transaction",
        "BUSINESS_RULE",
        null,
        "Customer must increase balance or reduce transaction amount"
    );

    private final String code;
    private final String message;
    private final String category;
    private final String dependencyName;
    private final String remediation;

    SoftFailureReason(String code, String message, String category,
                     String dependencyName, String remediation) {
        this.code = code;
        this.message = message;
        this.category = category;
        this.dependencyName = dependencyName;
        this.remediation = remediation;
    }

    public String code() { return code; }
    public String message() { return message; }
    public String category() { return category; }
    public String dependencyName() { return dependencyName; }
    public String remediation() { return remediation; }
}
