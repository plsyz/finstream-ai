package com.finstream.service;

import com.finstream.agents.GraphAnomalyAgent;
import com.finstream.agents.SanitizationAgent;
import com.finstream.agents.SemanticClassifierAgent;
import com.finstream.domain.ProcessedTransaction;
import com.finstream.domain.RawTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Pipeline Orchestrator — the central coordinator of the FinStream agent chain.
 *
 * Consumes raw transactions from Kafka, routes them sequentially through
 * three specialized agents, then publishes the fully processed result:
 *
 *   raw-transactions (Kafka)
 *       |
 *       v
 *   [1] SanitizationAgent  — mask PII (regex, no LLM)
 *       |
 *       v
 *   [2] SemanticClassifierAgent — categorize via pgvector RAG + LLM
 *       |
 *       v
 *   [3] GraphAnomalyAgent — detect fraud via Neo4j subgraph + LLM
 *       |
 *       v
 *   processed-transactions (Kafka)
 */
@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final SanitizationAgent sanitizationAgent;
    private final SemanticClassifierAgent classifierAgent;
    private final GraphAnomalyAgent anomalyAgent;
    private final KafkaTemplate<String, ProcessedTransaction> processedKafkaTemplate;

    @Value("${finstream.kafka.topics.processed-transactions}")
    private String processedTopic;

    public PipelineOrchestrator(SanitizationAgent sanitizationAgent,
                                SemanticClassifierAgent classifierAgent,
                                GraphAnomalyAgent anomalyAgent,
                                KafkaTemplate<String, ProcessedTransaction> processedKafkaTemplate) {
        this.sanitizationAgent = sanitizationAgent;
        this.classifierAgent = classifierAgent;
        this.anomalyAgent = anomalyAgent;
        this.processedKafkaTemplate = processedKafkaTemplate;
    }

    /**
     * Kafka listener that consumes raw transactions and drives them through the agent chain.
     */
    @KafkaListener(
            topics = "${finstream.kafka.topics.raw-transactions}",
            containerFactory = "rawTransactionListenerFactory"
    )
    public void processTransaction(RawTransaction raw) {
        log.info("=== Pipeline START for transaction {} ===", raw.transactionId());
        long startTime = System.currentTimeMillis();

        try {
            // Agent 1: Sanitize PII
            SanitizationAgent.SanitizedData sanitized = sanitizationAgent.sanitize(raw);

            // Agent 2: Classify via Semantic RAG
            SemanticClassifierAgent.ClassificationResult classification = classifierAgent.classify(raw);

            // Agent 3: Anomaly detection via GraphRAG
            GraphAnomalyAgent.AnomalyResult anomaly = anomalyAgent.assessRisk(raw);

            // Assemble the ProcessedTransaction
            ProcessedTransaction processed = new ProcessedTransaction(
                    raw.transactionId(),
                    sanitized.maskedAccountHolder(),
                    sanitized.maskedAccountNumber(),
                    sanitized.maskedEmail(),
                    raw.merchantName(),
                    raw.merchantId(),
                    raw.amount(),
                    raw.currency(),
                    raw.country(),
                    sanitized.maskedIpAddress(),
                    classification.category(),
                    classification.confidence(),
                    anomaly.riskLevel(),
                    anomaly.reason(),
                    anomaly.flagged(),
                    raw.timestamp(),
                    Instant.now()
            );

            // Publish to egress topic
            processedKafkaTemplate.send(processedTopic, raw.transactionId(), processed);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("=== Pipeline COMPLETE for transaction {} | Category: {} | Risk: {} | {}ms ===",
                    raw.transactionId(), classification.category(), anomaly.riskLevel(), elapsed);

        } catch (Exception e) {
            log.error("=== Pipeline FAILED for transaction {} ===", raw.transactionId(), e);
            throw e;
        }
    }

    /**
     * Synchronous processing for REST API calls that bypass Kafka.
     * Useful for testing and demo purposes.
     */
    public ProcessedTransaction processTransactionSync(RawTransaction raw) {
        log.info("=== Sync Pipeline START for transaction {} ===", raw.transactionId());
        long startTime = System.currentTimeMillis();

        SanitizationAgent.SanitizedData sanitized = sanitizationAgent.sanitize(raw);
        SemanticClassifierAgent.ClassificationResult classification = classifierAgent.classify(raw);
        GraphAnomalyAgent.AnomalyResult anomaly = anomalyAgent.assessRisk(raw);

        ProcessedTransaction processed = new ProcessedTransaction(
                raw.transactionId(),
                sanitized.maskedAccountHolder(),
                sanitized.maskedAccountNumber(),
                sanitized.maskedEmail(),
                raw.merchantName(),
                raw.merchantId(),
                raw.amount(),
                raw.currency(),
                raw.country(),
                sanitized.maskedIpAddress(),
                classification.category(),
                classification.confidence(),
                anomaly.riskLevel(),
                anomaly.reason(),
                anomaly.flagged(),
                raw.timestamp(),
                Instant.now()
        );

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== Sync Pipeline COMPLETE for transaction {} | {}ms ===",
                raw.transactionId(), elapsed);

        return processed;
    }
}
