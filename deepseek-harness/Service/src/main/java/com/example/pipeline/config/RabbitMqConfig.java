package com.example.pipeline.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares the RabbitMQ exchange/queue/binding and the Kafka input topic.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "pipeline.exchange";
    public static final String QUEUE = "pipeline.queue";
    public static final String ROUTING_KEY = "pipeline.routing";

    @Bean
    public TopicExchange pipelineExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue pipelineQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding pipelineBinding(Queue pipelineQueue, TopicExchange pipelineExchange) {
        return BindingBuilder.bind(pipelineQueue).to(pipelineExchange).with(ROUTING_KEY);
    }

    @Bean
    public KafkaAdmin.NewTopics pipelineKafkaTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("input-topic")
                        .partitions(3)
                        .replicas(3)
                        .build());
    }
}
