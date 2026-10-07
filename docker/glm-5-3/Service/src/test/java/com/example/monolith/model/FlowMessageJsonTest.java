package com.example.monolith.model;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowMessageJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializeCarriesContractFields() throws Exception {
        FlowMessage message = new FlowMessage("cid-1", "hello", List.of(
                new Enrichment("KAFKA", "2026-10-08T12:00:00Z", "consumed from partition 1 offset 42"),
                new Enrichment("RABBITMQ", "2026-10-08T12:00:01Z", "consumed from queue messages.queue")));

        String json = objectMapper.writeValueAsString(message);

        assertThat(json).contains("\"correlationId\":\"cid-1\"");
        assertThat(json).contains("\"originalMessage\":\"hello\"");
        assertThat(json).contains("\"stage\":\"KAFKA\"");
        assertThat(json).contains("\"stage\":\"RABBITMQ\"");
        assertThat(json).contains("\"appliedAt\":\"2026-10-08T12:00:00Z\"");
    }

    @Test
    void roundTripPreservesMessage() throws Exception {
        FlowMessage message = FlowMessage.initial("cid-2", "ping")
                .withEnrichment(new Enrichment("KAFKA", "2026-10-08T12:00:00Z", "consumed from partition 0"))
                .withEnrichment(new Enrichment("RABBITMQ", "2026-10-08T12:00:01Z", "consumed from messages.queue"));

        String json = objectMapper.writeValueAsString(message);
        FlowMessage back = objectMapper.readValue(json, FlowMessage.class);

        assertThat(back).isEqualTo(message);
        assertThat(back.enrichments()).hasSize(2);
    }

    @Test
    void withEnrichmentDoesNotMutateOriginal() {
        FlowMessage original = FlowMessage.initial("cid-3", "hello");

        FlowMessage enriched = original.withEnrichment(
                new Enrichment("KAFKA", "2026-10-08T12:00:00Z", "consumed from partition 2"));

        assertThat(original.enrichments()).isEmpty();
        assertThat(enriched.enrichments()).hasSize(1);
        assertThat(enriched.correlationId()).isEqualTo(original.correlationId());
        assertThat(enriched.originalMessage()).isEqualTo(original.originalMessage());
    }
}
