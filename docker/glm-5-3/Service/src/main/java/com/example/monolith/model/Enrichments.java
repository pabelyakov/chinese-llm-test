package com.example.monolith.model;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;

public final class Enrichments {

    private Enrichments() {
    }

    public static Enrichment kafkaStage(ConsumerRecord<?, ?> record) {
        String note = "consumed from partition %d offset %d timestamp %d"
                .formatted(record.partition(), record.offset(), record.timestamp());
        return new Enrichment("KAFKA", Instant.now().toString(), note);
    }

    public static Enrichment rabbitStage(String queue, long deliveryTag, boolean redelivered) {
        String note = "consumed from queue %s deliveryTag %d redelivered %s"
                .formatted(queue, deliveryTag, redelivered);
        return new Enrichment("RABBITMQ", Instant.now().toString(), note);
    }
}
