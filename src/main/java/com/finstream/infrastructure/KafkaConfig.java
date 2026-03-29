package com.finstream.infrastructure;

import com.finstream.domain.ProcessedTransaction;
import com.finstream.domain.RawTransaction;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka infrastructure configuration for the FinStream pipeline.
 * Defines topics, producer/consumer factories, and templates for both
 * raw ingestion and processed egress message flows.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${finstream.kafka.topics.raw-transactions}")
    private String rawTransactionsTopic;

    @Value("${finstream.kafka.topics.processed-transactions}")
    private String processedTransactionsTopic;

    // --- Topics ---

    @Bean
    public NewTopic rawTransactionsTopic() {
        return TopicBuilder.name(rawTransactionsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic processedTransactionsTopic() {
        return TopicBuilder.name(processedTransactionsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // --- Producer for RawTransaction (ingestion -> Kafka) ---

    @Bean
    public ProducerFactory<String, RawTransaction> rawTransactionProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, RawTransaction> rawTransactionKafkaTemplate() {
        return new KafkaTemplate<>(rawTransactionProducerFactory());
    }

    // --- Producer for ProcessedTransaction (pipeline -> Kafka egress) ---

    @Bean
    public ProducerFactory<String, ProcessedTransaction> processedTransactionProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, ProcessedTransaction> processedTransactionKafkaTemplate() {
        return new KafkaTemplate<>(processedTransactionProducerFactory());
    }

    // --- Consumer for RawTransaction (pipeline orchestrator reads from this) ---

    @Bean
    public ConsumerFactory<String, RawTransaction> rawTransactionConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "finstream-pipeline");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.finstream.domain");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, RawTransaction.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RawTransaction> rawTransactionListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, RawTransaction> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(rawTransactionConsumerFactory());
        factory.setConcurrency(3);
        return factory;
    }
}
