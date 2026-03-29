package com.finstream.agents;

import com.finstream.domain.RawTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 2: Semantic Classifier Agent (pgvector RAG).
 *
 * Uses Retrieval-Augmented Generation to classify transactions into semantic categories.
 * The agent:
 *   1. Builds a query string from the transaction's merchant + description + amount
 *   2. Searches pgvector for similar past transactions/regulation context
 *   3. Sends the retrieved context + transaction details to the LLM
 *   4. Parses the structured category response
 *
 * Categories: Software, Meals & Entertainment, Travel, Professional Services,
 *             Office Supplies, Financial Services, Healthcare, Retail, Utilities, Other
 */
@Component
public class SemanticClassifierAgent {

    private static final Logger log = LoggerFactory.getLogger(SemanticClassifierAgent.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    private static final String CLASSIFICATION_PROMPT = """
            You are a financial transaction classifier. Based on the transaction details and
            any similar past transaction context provided, classify this transaction into
            EXACTLY ONE of these categories:

            - Software
            - Meals & Entertainment
            - Travel
            - Professional Services
            - Office Supplies
            - Financial Services
            - Healthcare
            - Retail
            - Utilities
            - Subscriptions
            - Other

            Transaction details:
            - Merchant: %s
            - Merchant Category: %s
            - Amount: %s %s
            - Description: %s
            - Country: %s

            Similar transaction context from knowledge base:
            %s

            Respond in EXACTLY this format (no other text):
            CATEGORY: <category name>
            CONFIDENCE: <0.0 to 1.0>
            """;

    public SemanticClassifierAgent(VectorStore vectorStore,
                                   @Qualifier("finstreamChatClient") ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    public ClassificationResult classify(RawTransaction transaction) {
        log.info("[Agent-2 Classifier] Classifying transaction {} (merchant: {})",
                transaction.transactionId(), transaction.merchantName());

        String searchQuery = buildSearchQuery(transaction);
        String retrievedContext = retrieveSimilarContext(searchQuery);

        String prompt = String.format(CLASSIFICATION_PROMPT,
                transaction.merchantName(),
                transaction.merchantCategory(),
                transaction.amount().toPlainString(),
                transaction.currency(),
                transaction.description(),
                transaction.country(),
                retrievedContext.isEmpty() ? "No similar transactions found." : retrievedContext
        );

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseClassificationResponse(response, transaction.transactionId());
    }

    private String buildSearchQuery(RawTransaction txn) {
        return String.format("Financial transaction: %s at %s for %s %s. Category: %s",
                txn.description() != null ? txn.description() : "",
                txn.merchantName() != null ? txn.merchantName() : "",
                txn.amount().toPlainString(),
                txn.currency(),
                txn.merchantCategory() != null ? txn.merchantCategory() : "unknown"
        );
    }

    private String retrieveSimilarContext(String query) {
        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(5)
                            .similarityThreshold(0.0)
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                log.debug("[Agent-2 Classifier] No similar documents found in vector store");
                return "";
            }

            return documents.stream()
                    .map(doc -> {
                        String source = doc.getMetadata()
                                .getOrDefault("regulation", "transaction-history").toString();
                        return String.format("[Source: %s] %s", source, doc.getText());
                    })
                    .collect(Collectors.joining("\n---\n"));

        } catch (Exception e) {
            log.warn("[Agent-2 Classifier] Vector search failed, classifying without context: {}",
                    e.getMessage());
            return "";
        }
    }

    private ClassificationResult parseClassificationResponse(String response, String txnId) {
        String category = "Other";
        double confidence = 0.5;

        try {
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("CATEGORY:")) {
                    category = line.substring("CATEGORY:".length()).trim();
                } else if (line.startsWith("CONFIDENCE:")) {
                    confidence = Double.parseDouble(line.substring("CONFIDENCE:".length()).trim());
                }
            }
        } catch (Exception e) {
            log.warn("[Agent-2 Classifier] Failed to parse LLM response for {}: {}", txnId, e.getMessage());
        }

        log.info("[Agent-2 Classifier] Transaction {} classified as '{}' (confidence: {})",
                txnId, category, confidence);

        return new ClassificationResult(category, confidence);
    }

    public record ClassificationResult(String category, double confidence) {}
}
