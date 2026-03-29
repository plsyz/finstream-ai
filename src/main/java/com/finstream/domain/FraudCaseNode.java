package com.finstream.domain;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j node representing a historical fraud case linked to merchants.
 * Provides the "memory" for the GraphAnomalyAgent — if a merchant was
 * involved in a prior fraud case, that context feeds into the LLM risk assessment.
 */
@Node("FraudCase")
public class FraudCaseNode {

    @Id
    @GeneratedValue
    private Long id;

    private String caseId;
    private String description;
    private String severity;    // LOW, MEDIUM, HIGH, CRITICAL
    private String detectedDate;
    private boolean resolved;

    public FraudCaseNode() {}

    public FraudCaseNode(String caseId, String description, String severity, String detectedDate) {
        this.caseId = caseId;
        this.description = description;
        this.severity = severity;
        this.detectedDate = detectedDate;
        this.resolved = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getDetectedDate() { return detectedDate; }
    public void setDetectedDate(String detectedDate) { this.detectedDate = detectedDate; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
