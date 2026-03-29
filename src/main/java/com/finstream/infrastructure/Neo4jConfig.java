package com.finstream.infrastructure;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;

/**
 * Explicit Neo4j transaction manager configuration.
 * Required when JPA (pgvector) and Neo4j coexist in the same Spring context —
 * without this, Spring Data can't resolve which transaction manager to use
 * and the Neo4j repositories get a null TransactionTemplate.
 */
@Configuration
public class Neo4jConfig {

    @Bean("neo4jTransactionManager")
    public Neo4jTransactionManager neo4jTransactionManager(Driver driver,
                                                           DatabaseSelectionProvider databaseSelectionProvider) {
        return new Neo4jTransactionManager(driver, databaseSelectionProvider);
    }
}
