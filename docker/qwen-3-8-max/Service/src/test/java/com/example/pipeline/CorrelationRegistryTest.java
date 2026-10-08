package com.example.pipeline;

import com.example.pipeline.correlation.CorrelationRegistry;
import com.example.pipeline.domain.Envelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationRegistryTest {

    @Test
    void registerAndGet() {
        CorrelationRegistry registry = new CorrelationRegistry();
        var entry = registry.register("cid-1");
        assertNotNull(entry);
        assertSame(entry, registry.get("cid-1"));
        assertEquals(1, registry.size());
        assertTrue(registry.pendingIds().contains("cid-1"));
    }

    @Test
    void completeResolvesFuture() throws Exception {
        CorrelationRegistry registry = new CorrelationRegistry();
        var entry = registry.register("cid-2");
        Envelope envelope = new Envelope("cid-2", "msg", "2026-01-01T00:00:00Z");

        assertTrue(registry.complete("cid-2", envelope));
        assertTrue(entry.future().isDone());
        assertSame(envelope, entry.future().get());
    }

    @Test
    void completeUnknownIdReturnsFalse() {
        CorrelationRegistry registry = new CorrelationRegistry();
        assertFalse(registry.complete("unknown", new Envelope("unknown", "m", "t")));
    }

    @Test
    void markStageTracksProgress() {
        CorrelationRegistry registry = new CorrelationRegistry();
        var entry = registry.register("cid-3");
        assertEquals("awaiting-kafka-produce", entry.lastStage().get());
        registry.markStage("cid-3", "kafka-enrichment");
        assertEquals("kafka-enrichment", entry.lastStage().get());
        registry.markStage("cid-3", "rabbit-enrichment");
        assertEquals("rabbit-enrichment", entry.lastStage().get());
        registry.markStage("missing", "whatever"); // no exception
    }

    @Test
    void removeCleansUpNoLeaks() {
        CorrelationRegistry registry = new CorrelationRegistry();
        registry.register("cid-4");
        assertNotNull(registry.remove("cid-4"));
        assertNull(registry.get("cid-4"));
        assertEquals(0, registry.size());
        assertNull(registry.remove("cid-4")); // idempotent
    }
}
