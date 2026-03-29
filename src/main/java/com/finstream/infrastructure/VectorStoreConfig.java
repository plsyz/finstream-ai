package com.finstream.infrastructure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for AI-related beans used by the FinStream pipeline agents.
 * The VectorStore itself is auto-configured by spring-ai-starter-vector-store-pgvector;
 * this config provides a dedicated ChatClient for the pipeline with a system prompt
 * tailored for financial transaction processing.
 */
@Configuration
public class VectorStoreConfig {

    @Bean("finstreamChatClient")
    public ChatClient finstreamChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultSystem("""
                    You are a financial transaction analysis engine operating within a real-time pipeline.
                    Your role is to analyze transactions with precision and provide structured, machine-parseable outputs.
                    Always respond in the exact format requested. Never add disclaimers or conversational text.
                    Base your analysis strictly on the context provided — do not hallucinate data points.
                    When assessing risk, be conservative: flag uncertain cases for human review rather than clearing them.
                    """)
                .build();
    }
}
