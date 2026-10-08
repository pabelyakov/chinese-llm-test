package com.example.pipeline;

import com.example.pipeline.domain.Envelope;
import com.example.pipeline.pipeline.EnrichmentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentServiceTest {

    private final EnrichmentService service = new EnrichmentService();

    @Test
    void enrichKafkaAddsStageWithMetadata() {
        Envelope envelope = new Envelope("cid-1", "hello", "2026-01-01T00:00:00Z");

        service.enrichKafka(envelope, "pipeline.inbound", 2, 42L, 1700000000000L, "broker-1");

        assertEquals(1, envelope.getStages().size());
        var stage = envelope.getStages().get(0);
        assertEquals("kafka-enrichment", stage.getStage());
        assertNotNull(stage.getAt());
        assertEquals("pipeline.inbound", stage.getData().get("topic"));
        assertEquals(2, stage.getData().get("partition"));
        assertEquals(42L, stage.getData().get("offset"));
        assertEquals(1700000000000L, stage.getData().get("timestamp"));
        assertEquals("broker-1", stage.getData().get("kafkaNodeId"));
        assertNotNull(stage.getData().get("hostname"));
    }

    @Test
    void enrichRabbitAddsStageWithMetadata() {
        Envelope envelope = new Envelope("cid-2", "hello", "2026-01-01T00:00:00Z");

        service.enrichRabbit(envelope, "pipeline.stage2.queue", 7L, "rabbit@rabbit1");

        assertEquals(1, envelope.getStages().size());
        var stage = envelope.getStages().get(0);
        assertEquals("rabbit-enrichment", stage.getStage());
        assertEquals("pipeline.stage2.queue", stage.getData().get("queue"));
        assertEquals("rabbit@rabbit1", stage.getData().get("node"));
        assertEquals(7L, stage.getData().get("deliveryTag"));
    }

    @Test
    void enrichmentsAccumulateInOrder() {
        Envelope envelope = new Envelope("cid-3", "hello", "2026-01-01T00:00:00Z");

        service.enrichKafka(envelope, "pipeline.inbound", 0, 1L, 123L, "broker-2");
        service.enrichRabbit(envelope, "pipeline.stage2.queue", 2L, null);

        assertEquals(2, envelope.getStages().size());
        assertEquals("kafka-enrichment", envelope.getStages().get(0).getStage());
        assertEquals("rabbit-enrichment", envelope.getStages().get(1).getStage());
        assertNotNull(envelope.getStages().get(1).getData().get("node"), "null node falls back to hostname");
    }

    @Test
    void nullKafkaNodeIdFallsBackToUnknown() {
        Envelope envelope = new Envelope("cid-5", "hello", "2026-01-01T00:00:00Z");
        service.enrichKafka(envelope, "t", 0, 0L, 0L, null);
        assertEquals("unknown", envelope.getStages().get(0).getData().get("kafkaNodeId"));
    }

    @Test
    void originalFieldsUntouched() {
        Envelope envelope = new Envelope("cid-4", "payload", "2026-01-01T00:00:00Z");
        service.enrichKafka(envelope, "t", 0, 0L, 0L, null);
        service.enrichRabbit(envelope, "q", 0L, "node");

        assertEquals("cid-4", envelope.getCorrelationId());
        assertEquals("payload", envelope.getOriginalMessage());
        assertEquals("2026-01-01T00:00:00Z", envelope.getCreatedAt());
    }
}
