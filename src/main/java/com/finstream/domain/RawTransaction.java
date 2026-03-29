package com.finstream.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable record representing a raw financial transaction as received from external sources.
 * This is the entry point into the FinStream pipeline — no PII masking or classification applied yet.
 */
public record RawTransaction(
        String transactionId,
        String accountHolder,       // PII — will be masked by SanitizationAgent
        String accountNumber,       // PII — will be masked by SanitizationAgent
        String email,               // PII — will be masked by SanitizationAgent
        String merchantName,
        String merchantId,
        String merchantCategory,    // MCC code or raw category from payment network
        BigDecimal amount,
        String currency,
        String country,
        String ipAddress,           // PII — will be masked by SanitizationAgent
        String description,
        Instant timestamp
) {
    public RawTransaction {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
