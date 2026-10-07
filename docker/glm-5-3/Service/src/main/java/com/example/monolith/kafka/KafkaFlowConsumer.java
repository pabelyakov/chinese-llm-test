package com.example.monolith.kafka;

import com.example.monolith.model.Enrichments;
import com.example.monolith.model.FlowMessage;
import com.example.monolith.rabbitmq.RabbitFlowPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaFlowConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaFlowConsumer.class);

    private final RabbitFlowPublisher rabbitFlowPublisher;
    private final ObjectMapper objectMapper;

    public KafkaFlowConsumer(RabbitFlowPublisher rabbitFlowPublisher, ObjectMapper objectMapper) {
        this.rabbitFlowPublisher = rabbitFlowPublisher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka-topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        FlowMessage message = parse(record.value());
        FlowMessage enriched = message.withEnrichment(Enrichments.kafkaStage(record));
        log.info("kafka stage correlationId={} partition={} offset={}", enriched.correlationId(),
                record.partition(), record.offset());
        rabbitFlowPublisher.publish(enriched);
    }

    private FlowMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, FlowMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unparseable kafka payload: " + payload, e);
        }
    }
}
