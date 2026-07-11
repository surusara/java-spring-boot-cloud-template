package com.example.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "risk_tier")
    private String riskTier;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(String paymentId, String accountId, BigDecimal amount, String currency,
                   String riskTier, String status, Instant createdAt) {
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.riskTier = riskTier;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRiskTier() {
        return riskTier;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
