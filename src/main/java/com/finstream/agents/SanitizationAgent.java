package com.finstream.agents;

import com.finstream.domain.RawTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Agent 1: PII Sanitization Agent.
 *
 * Masks personally identifiable information (PII) in raw transactions using
 * deterministic regex patterns. No LLM calls — this agent is pure Java for
 * speed and predictability in the hot path.
 *
 * Masked fields:
 * - Account holder name: "John Doe" -> "J*** D**"
 * - Account number: "DE89370400440532013000" -> "DE89***************3000"
 * - Email: "john@example.com" -> "j***@***.com"
 * - IP Address: "192.168.1.100" -> "192.***.***. 100"
 */
@Component
public class SanitizationAgent {

    private static final Logger log = LoggerFactory.getLogger(SanitizationAgent.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(.)([^@]*)(@)(.)([^.]*)(\\..+)$"
    );
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$"
    );

    /**
     * Sanitizes all PII fields in the raw transaction and returns a record
     * containing only the masked versions of sensitive data.
     */
    public SanitizedData sanitize(RawTransaction raw) {
        log.info("[Agent-1 Sanitizer] Processing transaction {}", raw.transactionId());

        String maskedName = maskName(raw.accountHolder());
        String maskedAccount = maskAccountNumber(raw.accountNumber());
        String maskedEmail = maskEmail(raw.email());
        String maskedIp = maskIpAddress(raw.ipAddress());

        log.debug("[Agent-1 Sanitizer] Masked PII for transaction {}", raw.transactionId());

        return new SanitizedData(maskedName, maskedAccount, maskedEmail, maskedIp);
    }

    String maskName(String name) {
        if (name == null || name.isBlank()) return "***";
        String[] parts = name.trim().split("\\s+");
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) masked.append(" ");
            if (parts[i].length() <= 1) {
                masked.append("*");
            } else {
                masked.append(parts[i].charAt(0));
                masked.append("*".repeat(parts[i].length() - 1));
            }
        }
        return masked.toString();
    }

    String maskAccountNumber(String account) {
        if (account == null || account.isBlank()) return "***";
        if (account.length() <= 8) return "****" + account.substring(account.length() - 2);
        return account.substring(0, 4) + "*".repeat(account.length() - 8) + account.substring(account.length() - 4);
    }

    String maskEmail(String email) {
        if (email == null || email.isBlank()) return "***";
        var matcher = EMAIL_PATTERN.matcher(email);
        if (matcher.matches()) {
            return matcher.group(1) + "***" + matcher.group(3) + "***" + matcher.group(6);
        }
        return "***@***.***";
    }

    String maskIpAddress(String ip) {
        if (ip == null || ip.isBlank()) return "***";
        var matcher = IP_PATTERN.matcher(ip);
        if (matcher.matches()) {
            return matcher.group(1) + ".***.***." + matcher.group(4);
        }
        return "***.***.***.***";
    }

    public record SanitizedData(
            String maskedAccountHolder,
            String maskedAccountNumber,
            String maskedEmail,
            String maskedIpAddress
    ) {}
}
