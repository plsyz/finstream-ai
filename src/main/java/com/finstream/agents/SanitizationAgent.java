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

    // --- Free-text scrubbing -------------------------------------------------
    //
    // The four structured fields above are masked, but PII can also appear inside
    // the free-text description ("refund to jane.smith@example.com", "IBAN DE89...").
    // The original pipeline forwarded the raw description to the LLM agents unchanged.
    // These helpers close that gap deterministically; see PipelineOrchestrator for the
    // sanitize-before-llm switch that controls whether the LLM agents receive the
    // scrubbed copy or the raw record.

    private static final Pattern FREE_TEXT_EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern FREE_TEXT_IBAN = Pattern.compile(
            "\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\\b");
    private static final Pattern FREE_TEXT_IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern FREE_TEXT_PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+\\d{1,3}[ -]?)?(?:\\(?\\d{2,4}\\)?[ -]?)\\d{3,4}[ -]?\\d{3,5}(?!\\w)");
    private static final Pattern FREE_TEXT_CARD = Pattern.compile(
            "\\b(?:\\d[ -]?){13,19}\\b");

    /**
     * Scrubs PII patterns from free text. In addition to pattern-based masking,
     * the account holder's name tokens are masked wherever they appear, so that
     * "Payment from Jane Smith" becomes "Payment from J*** S****".
     */
    String scrubFreeText(String text, String accountHolder) {
        if (text == null || text.isBlank()) return text;
        String scrubbed = text;
        scrubbed = FREE_TEXT_EMAIL.matcher(scrubbed).replaceAll(m -> maskEmail(m.group()));
        scrubbed = FREE_TEXT_IBAN.matcher(scrubbed).replaceAll(m -> maskAccountNumber(m.group().replace(" ", "")));
        scrubbed = FREE_TEXT_CARD.matcher(scrubbed).replaceAll("****-****-****-****");
        scrubbed = FREE_TEXT_IPV4.matcher(scrubbed).replaceAll(m -> maskIpAddress(m.group()));
        scrubbed = FREE_TEXT_PHONE.matcher(scrubbed).replaceAll("+**-***-*****");
        if (accountHolder != null && !accountHolder.isBlank()) {
            for (String token : accountHolder.trim().split("\\s+")) {
                if (token.length() < 3) continue;
                Pattern nameToken = Pattern.compile("(?i)\\b" + Pattern.quote(token) + "\\b");
                String masked = token.charAt(0) + "*".repeat(token.length() - 1);
                scrubbed = nameToken.matcher(scrubbed).replaceAll(masked);
            }
        }
        return scrubbed;
    }

    /**
     * Returns a copy of the transaction that is safe to hand to the LLM agents:
     * structured PII fields replaced with their masked forms and the free-text
     * description scrubbed. Non-PII fields (merchant, amount, country, ...) are untouched.
     */
    public RawTransaction sanitizeForLlm(RawTransaction raw) {
        return new RawTransaction(
                raw.transactionId(),
                maskName(raw.accountHolder()),
                maskAccountNumber(raw.accountNumber()),
                maskEmail(raw.email()),
                raw.merchantName(),
                raw.merchantId(),
                raw.merchantCategory(),
                raw.amount(),
                raw.currency(),
                raw.country(),
                maskIpAddress(raw.ipAddress()),
                scrubFreeText(raw.description(), raw.accountHolder()),
                raw.timestamp()
        );
    }

    public record SanitizedData(
            String maskedAccountHolder,
            String maskedAccountNumber,
            String maskedEmail,
            String maskedIpAddress
    ) {}
}
