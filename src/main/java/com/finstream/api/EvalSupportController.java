package com.finstream.api;

import com.finstream.agents.GraphAnomalyAgent;
import com.finstream.agents.SanitizationAgent;
import com.finstream.agents.SemanticClassifierAgent;
import com.finstream.domain.ProcessedTransaction;
import com.finstream.domain.RawTransaction;
import com.finstream.service.PipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluation support endpoints. Enabled only with {@code finstream.eval.enabled=true};
 * never exposed in a production profile.
 *
 * Purpose: make each agent callable in isolation and allow the eval harness to
 * seed the knowledge graph and vector store deterministically, so that
 * subtask capability (each agent alone) can be compared with composite
 * end-to-end behaviour (the full chain).
 *
 * All endpoints accept an optional {@code mode} query parameter:
 *   mode=raw        -> LLM agents receive the raw transaction (original pipeline behaviour)
 *   mode=sanitized  -> LLM agents receive the masked + scrubbed copy
 *   (absent)        -> whatever finstream.pipeline.sanitize-before-llm is set to
 */
@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(name = "finstream.eval.enabled", havingValue = "true")
public class EvalSupportController {

    private static final Logger log = LoggerFactory.getLogger(EvalSupportController.class);

    private final SanitizationAgent sanitizationAgent;
    private final SemanticClassifierAgent classifierAgent;
    private final GraphAnomalyAgent anomalyAgent;
    private final PipelineOrchestrator orchestrator;
    private final VectorStore vectorStore;
    private final Neo4jClient neo4jClient;

    public EvalSupportController(SanitizationAgent sanitizationAgent,
                                 SemanticClassifierAgent classifierAgent,
                                 GraphAnomalyAgent anomalyAgent,
                                 PipelineOrchestrator orchestrator,
                                 VectorStore vectorStore,
                                 Neo4jClient neo4jClient) {
        this.sanitizationAgent = sanitizationAgent;
        this.classifierAgent = classifierAgent;
        this.anomalyAgent = anomalyAgent;
        this.orchestrator = orchestrator;
        this.vectorStore = vectorStore;
        this.neo4jClient = neo4jClient;
    }

    // ---------------------------------------------------------------------
    // Isolated agent calls (subtask capability)
    // ---------------------------------------------------------------------

    @PostMapping("/agents/sanitize")
    public SanitizationAgent.SanitizedData sanitize(@RequestBody RawTransaction raw) {
        return sanitizationAgent.sanitize(raw);
    }

    /** Returns the exact record the LLM agents would receive in the given mode. */
    @PostMapping("/agents/llm-input")
    public RawTransaction llmInput(@RequestBody RawTransaction raw,
                                   @RequestParam(required = false) String mode) {
        return orchestrator.prepareLlmInput(raw, resolveMode(mode));
    }

    @PostMapping("/agents/classify")
    public SemanticClassifierAgent.ClassificationResult classify(@RequestBody RawTransaction raw,
                                                                 @RequestParam(required = false) String mode) {
        return classifierAgent.classify(orchestrator.prepareLlmInput(raw, resolveMode(mode)));
    }

    @PostMapping("/agents/risk")
    public GraphAnomalyAgent.AnomalyResult risk(@RequestBody RawTransaction raw,
                                                @RequestParam(required = false) String mode) {
        return anomalyAgent.assessRisk(orchestrator.prepareLlmInput(raw, resolveMode(mode)));
    }

    // ---------------------------------------------------------------------
    // Composite call (end-to-end) with the LLM input echoed back for leak audit
    // ---------------------------------------------------------------------

    @PostMapping("/pipeline")
    public Map<String, Object> pipeline(@RequestBody RawTransaction raw,
                                        @RequestParam(required = false) String mode) {
        boolean sanitize = resolveMode(mode);
        RawTransaction llmInput = orchestrator.prepareLlmInput(raw, sanitize);
        ProcessedTransaction processed = orchestrator.processTransactionSync(raw, sanitize);
        return Map.of(
                "mode", sanitize ? "sanitized" : "raw",
                "llmInput", llmInput,
                "processed", processed
        );
    }

    // ---------------------------------------------------------------------
    // Deterministic seeding of the two retrieval stores
    // ---------------------------------------------------------------------

    public record SeedMerchant(String merchantId, String name, String category, String country,
                               String registeredAddress, String ipAddress,
                               boolean flagged, String flagReason) {}

    public record SeedRelationship(String fromMerchantId, String type, String toMerchantId) {}

    public record SeedFraudCase(String caseId, String merchantId, String description,
                                String severity, String detectedDate, boolean resolved) {}

    public record GraphSeedRequest(List<SeedMerchant> merchants,
                                   List<SeedRelationship> relationships,
                                   List<SeedFraudCase> fraudCases) {}

    @DeleteMapping("/seed/graph")
    public ResponseEntity<Map<String, String>> clearGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        log.warn("[Eval] Knowledge graph cleared");
        return ResponseEntity.ok(Map.of("status", "CLEARED"));
    }

    @PostMapping("/seed/graph")
    public ResponseEntity<Map<String, Object>> seedGraph(@RequestBody GraphSeedRequest request) {
        int merchants = 0, relationships = 0, cases = 0;

        for (SeedMerchant m : request.merchants()) {
            neo4jClient.query("""
                    MERGE (m:Merchant {merchantId: $merchantId})
                    SET m.name = $name, m.category = $category, m.country = $country,
                        m.registeredAddress = $registeredAddress, m.ipAddress = $ipAddress,
                        m.flagged = $flagged, m.flagReason = $flagReason
                    """)
                    .bindAll(Map.of(
                            "merchantId", m.merchantId(),
                            "name", m.name(),
                            "category", nz(m.category()),
                            "country", nz(m.country()),
                            "registeredAddress", nz(m.registeredAddress()),
                            "ipAddress", nz(m.ipAddress()),
                            "flagged", m.flagged(),
                            "flagReason", nz(m.flagReason())))
                    .run();
            merchants++;
        }

        for (SeedRelationship r : request.relationships()) {
            String type = switch (r.type()) {
                case "SHARES_IP_WITH", "SHARES_ADDRESS_WITH" -> r.type();
                default -> throw new IllegalArgumentException("Unsupported relationship type: " + r.type());
            };
            neo4jClient.query("""
                    MATCH (a:Merchant {merchantId: $from}), (b:Merchant {merchantId: $to})
                    MERGE (a)-[:%s]->(b)
                    """.formatted(type))
                    .bindAll(Map.of("from", r.fromMerchantId(), "to", r.toMerchantId()))
                    .run();
            relationships++;
        }

        for (SeedFraudCase fc : request.fraudCases()) {
            neo4jClient.query("""
                    MATCH (m:Merchant {merchantId: $merchantId})
                    MERGE (fc:FraudCase {caseId: $caseId})
                    SET fc.description = $description, fc.severity = $severity,
                        fc.detectedDate = $detectedDate, fc.resolved = $resolved
                    MERGE (m)-[:PREVIOUSLY_FLAGGED_IN]->(fc)
                    """)
                    .bindAll(Map.of(
                            "merchantId", fc.merchantId(),
                            "caseId", fc.caseId(),
                            "description", nz(fc.description()),
                            "severity", nz(fc.severity()),
                            "detectedDate", nz(fc.detectedDate()),
                            "resolved", fc.resolved()))
                    .run();
            cases++;
        }

        log.info("[Eval] Graph seeded: {} merchants, {} relationships, {} fraud cases",
                merchants, relationships, cases);
        return ResponseEntity.ok(Map.of(
                "merchants", merchants, "relationships", relationships, "fraudCases", cases));
    }

    public record SeedDocument(String text, Map<String, Object> metadata) {}

    @PostMapping("/seed/vectors")
    public ResponseEntity<Map<String, Integer>> seedVectors(@RequestBody List<SeedDocument> docs) {
        List<Document> documents = new ArrayList<>();
        for (SeedDocument d : docs) {
            documents.add(new Document(d.text(), d.metadata() == null ? Map.of() : d.metadata()));
        }
        vectorStore.add(documents);
        log.info("[Eval] Vector store seeded with {} documents", documents.size());
        return ResponseEntity.ok(Map.of("documents", documents.size()));
    }

    // ---------------------------------------------------------------------

    private boolean resolveMode(String mode) {
        if (mode == null || mode.isBlank()) return orchestrator.isSanitizeBeforeLlm();
        return switch (mode.toLowerCase()) {
            case "raw" -> false;
            case "sanitized" -> true;
            default -> throw new IllegalArgumentException("mode must be 'raw' or 'sanitized'");
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
