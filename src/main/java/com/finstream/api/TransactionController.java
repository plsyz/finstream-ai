package com.finstream.api;

import com.finstream.domain.ProcessedTransaction;
import com.finstream.domain.RawTransaction;
import com.finstream.service.PipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for ingesting financial transactions into the FinStream pipeline.
 *
 * Two modes:
 * - Async (POST /api/transactions): Publishes to Kafka and returns immediately.
 * - Sync (POST /api/transactions/process): Runs the full agent chain and returns result.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final KafkaTemplate<String, RawTransaction> rawKafkaTemplate;
    private final PipelineOrchestrator orchestrator;

    @Value("${finstream.kafka.topics.raw-transactions}")
    private String rawTopic;

    public TransactionController(KafkaTemplate<String, RawTransaction> rawKafkaTemplate,
                                 PipelineOrchestrator orchestrator) {
        this.rawKafkaTemplate = rawKafkaTemplate;
        this.orchestrator = orchestrator;
    }

    /**
     * Async ingestion: publishes to Kafka and returns immediately.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> ingestTransaction(@RequestBody RawTransaction transaction) {
        log.info("Received transaction {} for async processing", transaction.transactionId());

        rawKafkaTemplate.send(rawTopic, transaction.transactionId(), transaction);

        return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "transactionId", transaction.transactionId(),
                "message", "Transaction queued for async pipeline processing"
        ));
    }

    /**
     * Sync processing: runs the full agent chain and returns the result immediately.
     */
    @PostMapping("/process")
    public ResponseEntity<ProcessedTransaction> processTransactionSync(
            @RequestBody RawTransaction transaction) {
        log.info("Received transaction {} for sync processing", transaction.transactionId());

        ProcessedTransaction processed = orchestrator.processTransactionSync(transaction);
        return ResponseEntity.ok(processed);
    }

    /**
     * Quick demo endpoint — generates a sample transaction and processes it synchronously.
     * No request body needed.
     */
    @PostMapping("/demo")
    public ResponseEntity<ProcessedTransaction> demoTransaction() {
        RawTransaction demo = new RawTransaction(
                "TXN-DEMO-" + UUID.randomUUID().toString().substring(0, 8),
                "Jane Smith",
                "DE89370400440532013000",
                "jane.smith@example.com",
                "TechCorp Solutions",
                "MERCHANT-TC-001",
                "5734",  // MCC: Computer Software Stores
                new BigDecimal("149.99"),
                "EUR",
                "Germany",
                "192.168.1.42",
                "Annual software license renewal - enterprise plan",
                Instant.now()
        );

        log.info("Demo transaction created: {}", demo.transactionId());
        ProcessedTransaction processed = orchestrator.processTransactionSync(demo);
        return ResponseEntity.ok(processed);
    }
}
