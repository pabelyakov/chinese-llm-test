package com.example.monolith.model;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentsTest {

    @Test
    void kafkaStageCarriesConsumerMetadata() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("messages.topic", 1, 42L, "cid-1", "{}");

        Enrichment enrichment = Enrichments.kafkaStage(record);

        assertThat(enrichment.stage()).isEqualTo("KAFKA");
        assertThat(enrichment.note())
                .contains("partition 1")
                .contains("offset 42")
                .contains("timestamp");
        assertThat(Instant.parse(enrichment.appliedAt())).isNotNull();
    }

    @Test
    void rabbitStageCarriesDeliveryMetadata() {
        Enrichment enrichment = Enrichments.rabbitStage("messages.queue", 7L, false);

        assertThat(enrichment.stage()).isEqualTo("RABBITMQ");
        assertThat(enrichment.note())
                .contains("queue messages.queue")
                .contains("deliveryTag 7")
                .contains("redelivered false");
        assertThat(Instant.parse(enrichment.appliedAt())).isNotNull();
    }
}
