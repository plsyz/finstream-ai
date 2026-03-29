package com.finstream.infrastructure;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.MapAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Executes the multi-hop Cypher subgraph query using Neo4jClient directly.
 * Spring Data Neo4j repositories can't auto-map multi-column Cypher results
 * to Map projections, so this service handles the raw driver-level mapping.
 */
@Service
public class MerchantRiskQueryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRiskQueryService.class);

    private final Neo4jClient neo4jClient;

    private static final String RISK_SUBGRAPH_QUERY = """
            MATCH (m:Merchant {merchantId: $merchantId})
            OPTIONAL MATCH (m)-[:SHARES_IP_WITH*1..2]-(related:Merchant)
            OPTIONAL MATCH (m)-[:SHARES_ADDRESS_WITH*1..2]-(addrRelated:Merchant)
            OPTIONAL MATCH (m)-[:PREVIOUSLY_FLAGGED_IN]->(fc:FraudCase)
            OPTIONAL MATCH (related)-[:PREVIOUSLY_FLAGGED_IN]->(rfc:FraudCase)
            RETURN m.merchantId AS merchantId,
                   m.name AS merchantName,
                   m.category AS category,
                   m.country AS country,
                   m.flagged AS isFlagged,
                   m.flagReason AS flagReason,
                   collect(DISTINCT {
                       relatedMerchant: related.name,
                       relatedMerchantId: related.merchantId,
                       relationship: 'SHARES_IP',
                       relatedFlagged: related.flagged,
                       relatedFlagReason: related.flagReason
                   }) AS ipConnections,
                   collect(DISTINCT {
                       relatedMerchant: addrRelated.name,
                       relatedMerchantId: addrRelated.merchantId,
                       relationship: 'SHARES_ADDRESS',
                       relatedFlagged: addrRelated.flagged,
                       relatedFlagReason: addrRelated.flagReason
                   }) AS addressConnections,
                   collect(DISTINCT {
                       caseId: fc.caseId,
                       description: fc.description,
                       severity: fc.severity,
                       resolved: fc.resolved
                   }) AS directFraudCases,
                   collect(DISTINCT {
                       caseId: rfc.caseId,
                       description: rfc.description,
                       severity: rfc.severity,
                       linkedVia: related.name
                   }) AS associatedFraudCases
            """;

    public MerchantRiskQueryService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Executes the multi-hop risk subgraph query and returns the results
     * as a list of maps suitable for formatting into LLM context.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> findMerchantRiskSubgraph(String merchantId) {
        Collection results = neo4jClient
                .query(RISK_SUBGRAPH_QUERY)
                .bind(merchantId).to("merchantId")
                .fetchAs(Map.class)
                .mappedBy((typeSystem, record) -> mapRecord(record))
                .all();

        return new ArrayList<>(results);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRecord(MapAccessor record) {
        Map<String, Object> row = new HashMap<>();
        row.put("merchantId", safeGet(record, "merchantId"));
        row.put("merchantName", safeGet(record, "merchantName"));
        row.put("category", safeGet(record, "category"));
        row.put("country", safeGet(record, "country"));
        row.put("isFlagged", safeGet(record, "isFlagged"));
        row.put("flagReason", safeGet(record, "flagReason"));
        row.put("ipConnections", safeGetList(record, "ipConnections"));
        row.put("addressConnections", safeGetList(record, "addressConnections"));
        row.put("directFraudCases", safeGetList(record, "directFraudCases"));
        row.put("associatedFraudCases", safeGetList(record, "associatedFraudCases"));
        return row;
    }

    private Object safeGet(MapAccessor record, String key) {
        try {
            Value val = record.get(key);
            if (val == null || val.isNull()) return null;
            return val.asObject();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> safeGetList(MapAccessor record, String key) {
        try {
            Value val = record.get(key);
            if (val == null || val.isNull()) return List.of();
            List<Object> rawList = val.asList(Value::asObject);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse list field '{}': {}", key, e.getMessage());
            return List.of();
        }
    }
}
