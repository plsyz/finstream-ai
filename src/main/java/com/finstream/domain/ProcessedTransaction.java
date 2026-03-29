package com.finstream.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable record representing a fully processed transaction after passing through
 * the entire FinStream agent pipeline: Sanitization -> Classification -> Anomaly Detection.
 *
 * All PII fields are masked, a semantic category is assigned, and a risk assessment is attached.
 */
public record ProcessedTransaction(
        String transactionId,
        String maskedAccountHolder,     // PII masked (e.g., "J*** D**")
        String maskedAccountNumber,     // PII masked (e.g., "****-****-****-1234")
        String maskedEmail,             // PII masked (e.g., "j***@***.com")
        String merchantName,
        String merchantId,
        BigDecimal amount,
        String currency,
        String country,
        String maskedIpAddress,         // PII masked (e.g., "192.***.***.1")

        // Classification output (Agent 2)
        String semanticCategory,        // e.g., "Software", "Meals & Entertainment", "Travel"
        double classificationConfidence,

        // Anomaly detection output (Agent 3)
        String riskLevel,               // LOW, MEDIUM, HIGH, CRITICAL
        String riskReason,              // Evidence-based explanation of risk assessment
        boolean flaggedForReview,

        Instant originalTimestamp,
        Instant processedTimestamp
) {
    public ProcessedTransaction {
        if (processedTimestamp == null) {
            processedTimestamp = Instant.now();
        }
    }
}
