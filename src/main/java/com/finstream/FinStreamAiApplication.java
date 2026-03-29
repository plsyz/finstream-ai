package com.finstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

@SpringBootApplication(exclude = { OllamaChatAutoConfiguration.class })
@EnableNeo4jRepositories(basePackages = "com.finstream.infrastructure", transactionManagerRef = "neo4jTransactionManager")
public class FinStreamAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinStreamAiApplication.class, args);
    }
}
