package com.example.pipeline.pipeline;

import com.example.pipeline.config.RabbitMqConfig;
import com.example.pipeline.model.PipelineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the message from Kafka, marks it {@code kafka-processed} and forwards
 * it to RabbitMQ.
 */
@Component
public class KafkaPipelineListener {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public KafkaPipelineListener(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${pipeline.kafka.topic:input-topic}")
    public void onMessage(String json) {
        PipelineMessage message = read(json);
        message.addStage("kafka", "kafka-processed");
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY, write(message));
    }

    private PipelineMessage read(String json) {
        try {
            return objectMapper.readValue(json, PipelineMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Kafka message", e);
        }
    }

    private String write(PipelineMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message", e);
        }
    }
}
