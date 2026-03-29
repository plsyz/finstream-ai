package com.finstream.infrastructure;

import com.finstream.domain.MerchantNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data Neo4j repository for basic Merchant CRUD operations.
 * Complex multi-column subgraph queries are handled by {@link MerchantRiskQueryService}
 * using Neo4jClient directly, because Spring Data Neo4j repositories cannot auto-map
 * multi-column Cypher results to Map projections.
 */
public interface GraphRepository extends Neo4jRepository<MerchantNode, Long> {

    Optional<MerchantNode> findByMerchantId(String merchantId);

    Optional<MerchantNode> findByName(String name);

    List<MerchantNode> findByFlaggedTrue();
}
