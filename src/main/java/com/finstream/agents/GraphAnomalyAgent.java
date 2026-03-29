package com.finstream.agents;

import com.finstream.domain.RawTransaction;
import com.finstream.infrastructure.MerchantRiskQueryService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent 3: Graph-based Anomaly Detector (GraphRAG).
 *
 * The most sophisticated agent in the pipeline. Uses Neo4j to execute a multi-hop
 * Cypher traversal that surfaces hidden relationships between the transaction's merchant
 * and known fraud entities. The subgraph context is then injected into an LLM prompt
 * for a final risk assessment.
 *
 * Risk signals detected:
 * - Merchant shares IP address with a flagged entity (proxy/shell company indicator)
 * - Merchant shares physical address with a flagged entity (fraud ring indicator)
 * - Merchant has direct prior fraud cases
 * - Merchant's network neighbors have prior fraud cases (guilt by association)
 *
 * Protected by Resilience4j @CircuitBreaker — if Neo4j or the LLM is down, the agent
 * returns a safe fallback that flags the transaction for manual review.
 */
@Component
public class GraphAnomalyAgent {

    private static final Logger log = LoggerFactory.getLogger(GraphAnomalyAgent.class);

    private final MerchantRiskQueryService riskQueryService;
    private final ChatClient chatClient;

    private static final String ANOMALY_DETECTION_PROMPT = """
            You are a fraud detection analyst integrated into a real-time financial transaction pipeline.
            You have been given a transaction and the result of a Neo4j knowledge graph query that shows
            the merchant's relationship network — specifically, connections to other merchants via shared
            IP addresses, shared physical addresses, and links to historical fraud cases.

            INTERPRETATION RULES:
            1. SHARES_IP connections: If the merchant shares an IP with a flagged entity, this is a
               STRONG fraud signal — it suggests the merchant may be a shell company or proxy.
            2. SHARES_ADDRESS connections: If the merchant shares a physical address with a flagged entity,
               this is a MODERATE-TO-STRONG fraud signal — it suggests co-location in a potential fraud ring.
            3. Direct fraud cases: If the merchant itself has prior fraud cases, this is a CRITICAL signal.
            4. Associated fraud cases: If a merchant's IP/address neighbor has fraud cases, this is a
               MODERATE signal — guilt by association requires further investigation.
            5. No connections found: If the subgraph is empty or shows no flagged neighbors, the merchant
               has no known graph-based risk signals. Assess based on transaction characteristics alone.

            TRANSACTION DETAILS:
            - Transaction ID: %s
            - Merchant: %s (ID: %s)
            - Amount: %s %s
            - Country: %s
            - Description: %s

            NEO4J SUBGRAPH CONTEXT:
            %s

            Respond in EXACTLY this format (no other text):
            RISK_LEVEL: <LOW|MEDIUM|HIGH|CRITICAL>
            FLAGGED: <true|false>
            REASON: <one-line explanation of the risk assessment, citing specific graph evidence>
            """;

    public GraphAnomalyAgent(MerchantRiskQueryService riskQueryService,
                             @Qualifier("finstreamChatClient") ChatClient chatClient) {
        this.riskQueryService = riskQueryService;
        this.chatClient = chatClient;
    }

    @CircuitBreaker(name = "graphAnomalyDetector", fallbackMethod = "assessRiskFallback")
    @Retry(name = "graphAnomalyDetector")
    public AnomalyResult assessRisk(RawTransaction transaction) {
        log.info("[Agent-3 Anomaly] Assessing risk for transaction {} (merchant: {})",
                transaction.transactionId(), transaction.merchantName());

        String subgraphContext = queryMerchantSubgraph(transaction.merchantId());

        String prompt = String.format(ANOMALY_DETECTION_PROMPT,
                transaction.transactionId(),
                transaction.merchantName(),
                transaction.merchantId(),
                transaction.amount().toPlainString(),
                transaction.currency(),
                transaction.country(),
                transaction.description(),
                subgraphContext.isEmpty()
                        ? "No merchant data found in knowledge graph. This is a new/unknown merchant."
                        : subgraphContext
        );

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseAnomalyResponse(response, transaction.transactionId());
    }

    @SuppressWarnings("unused")
    private AnomalyResult assessRiskFallback(RawTransaction transaction, Throwable t) {
        log.warn("[Agent-3 Anomaly] Circuit breaker fallback for transaction {}. Reason: {}",
                transaction.transactionId(), t.getMessage());

        return new AnomalyResult(
                "MEDIUM",
                "Automated risk assessment unavailable — circuit breaker open. Flagged for manual review.",
                true
        );
    }

    private String queryMerchantSubgraph(String merchantId) {
        try {
            List<Map<String, Object>> subgraph = riskQueryService.findMerchantRiskSubgraph(merchantId);
            if (subgraph == null || subgraph.isEmpty()) {
                return "";
            }
            return formatSubgraphForLLM(subgraph);
        } catch (Exception e) {
            log.warn("[Agent-3 Anomaly] Neo4j query failed for merchant {}: {}", merchantId, e.getMessage());
            return "ERROR: Could not retrieve merchant subgraph — " + e.getMessage();
        }
    }

    /**
     * Formats raw Cypher query results into a structured, readable context string
     * that the LLM can interpret according to the system prompt rules.
     */
    @SuppressWarnings("unchecked")
    private String formatSubgraphForLLM(List<Map<String, Object>> subgraph) {
        StringBuilder context = new StringBuilder();

        for (Map<String, Object> row : subgraph) {
            context.append("MERCHANT PROFILE:\n");
            context.append(String.format("  Name: %s | ID: %s | Category: %s | Country: %s\n",
                    row.get("merchantName"), row.get("merchantId"),
                    row.get("category"), row.get("country")));
            context.append(String.format("  Directly Flagged: %s | Flag Reason: %s\n",
                    row.get("isFlagged"), row.getOrDefault("flagReason", "N/A")));

            List<Map<String, Object>> ipConns = (List<Map<String, Object>>) row.get("ipConnections");
            if (ipConns != null && !ipConns.isEmpty()) {
                context.append("\nSHARED IP CONNECTIONS:\n");
                for (Map<String, Object> conn : ipConns) {
                    if (conn.get("relatedMerchant") != null) {
                        context.append(String.format("  - %s (ID: %s) | Flagged: %s | Reason: %s\n",
                                conn.get("relatedMerchant"), conn.get("relatedMerchantId"),
                                conn.get("relatedFlagged"),
                                conn.getOrDefault("relatedFlagReason", "N/A")));
                    }
                }
            }

            List<Map<String, Object>> addrConns = (List<Map<String, Object>>) row.get("addressConnections");
            if (addrConns != null && !addrConns.isEmpty()) {
                context.append("\nSHARED ADDRESS CONNECTIONS:\n");
                for (Map<String, Object> conn : addrConns) {
                    if (conn.get("relatedMerchant") != null) {
                        context.append(String.format("  - %s (ID: %s) | Flagged: %s | Reason: %s\n",
                                conn.get("relatedMerchant"), conn.get("relatedMerchantId"),
                                conn.get("relatedFlagged"),
                                conn.getOrDefault("relatedFlagReason", "N/A")));
                    }
                }
            }

            List<Map<String, Object>> directCases = (List<Map<String, Object>>) row.get("directFraudCases");
            if (directCases != null && !directCases.isEmpty()) {
                context.append("\nDIRECT FRAUD HISTORY:\n");
                for (Map<String, Object> fc : directCases) {
                    if (fc.get("caseId") != null) {
                        context.append(String.format("  - Case %s [%s]: %s (Resolved: %s)\n",
                                fc.get("caseId"), fc.get("severity"),
                                fc.get("description"), fc.get("resolved")));
                    }
                }
            }

            List<Map<String, Object>> assocCases = (List<Map<String, Object>>) row.get("associatedFraudCases");
            if (assocCases != null && !assocCases.isEmpty()) {
                context.append("\nASSOCIATED FRAUD (via network neighbors):\n");
                for (Map<String, Object> fc : assocCases) {
                    if (fc.get("caseId") != null) {
                        context.append(String.format("  - Case %s [%s]: %s (linked via: %s)\n",
                                fc.get("caseId"), fc.get("severity"),
                                fc.get("description"), fc.get("linkedVia")));
                    }
                }
            }
        }

        return context.toString();
    }

    private AnomalyResult parseAnomalyResponse(String response, String txnId) {
        String riskLevel = "MEDIUM";
        String reason = "Unable to parse risk assessment";
        boolean flagged = true;

        try {
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("RISK_LEVEL:")) {
                    riskLevel = line.substring("RISK_LEVEL:".length()).trim();
                } else if (line.startsWith("FLAGGED:")) {
                    flagged = Boolean.parseBoolean(line.substring("FLAGGED:".length()).trim());
                } else if (line.startsWith("REASON:")) {
                    reason = line.substring("REASON:".length()).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[Agent-3 Anomaly] Failed to parse LLM response for {}: {}", txnId, e.getMessage());
        }

        log.info("[Agent-3 Anomaly] Transaction {} risk: {} (flagged: {})", txnId, riskLevel, flagged);
        return new AnomalyResult(riskLevel, reason, flagged);
    }

    public record AnomalyResult(String riskLevel, String reason, boolean flagged) {}
}
