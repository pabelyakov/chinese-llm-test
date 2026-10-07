package com.example.monolith.kafka;

import java.util.concurrent.TimeUnit;

import com.example.monolith.config.AppProperties;
import com.example.monolith.flow.KafkaPublishException;
import com.example.monolith.model.FlowMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public KafkaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          ObjectMapper objectMapper,
                          AppProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(FlowMessage message) {
        String payload = serialize(message);
        try {
            kafkaTemplate.send(properties.kafkaTopic(), message.correlationId(), payload)
                    .get(properties.kafkaSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("published correlationId={} to kafka topic={}", message.correlationId(),
                    properties.kafkaTopic());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException(message.correlationId(), e);
        } catch (Exception e) {
            throw new KafkaPublishException(message.correlationId(), e);
        }
    }

    private String serialize(FlowMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new KafkaPublishException(message.correlationId(), e);
        }
    }
}
