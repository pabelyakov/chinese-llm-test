package com.example.pipeline.correlation;

import com.example.pipeline.domain.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Реестр in-flight запросов: correlationId -> future итогового envelope.
 * Обязательная очистка по завершении/таймауту (remove вызывается контроллером в finally).
 */
@Component
public class CorrelationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CorrelationRegistry.class);

    public record Entry(CompletableFuture<Envelope> future,
                        AtomicReference<String> lastStage,
                        long registeredAtMillis) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Entry register(String correlationId) {
        Entry entry = new Entry(new CompletableFuture<>(),
                new AtomicReference<>("awaiting-kafka-produce"),
                System.currentTimeMillis());
        entries.put(correlationId, entry);
        log.info("[{}] registered in CorrelationRegistry (pending={})", correlationId, entries.size());
        return entry;
    }

    public Entry get(String correlationId) {
        return entries.get(correlationId);
    }

    public void markStage(String correlationId, String stage) {
        Entry entry = entries.get(correlationId);
        if (entry != null) {
            entry.lastStage().set(stage);
        }
    }

    public boolean complete(String correlationId, Envelope envelope) {
        Entry entry = entries.get(correlationId);
        if (entry == null) {
            return false;
        }
        boolean done = entry.future().complete(envelope);
        if (done) {
            log.info("[{}] CompletableFuture completed", correlationId);
        }
        return done;
    }

    public Entry remove(String correlationId) {
        Entry removed = entries.remove(correlationId);
        if (removed != null) {
            log.info("[{}] removed from CorrelationRegistry (pending={})", correlationId, entries.size());
        }
        return removed;
    }

    public Set<String> pendingIds() {
        return Set.copyOf(entries.keySet());
    }

    public int size() {
        return entries.size();
    }
}
