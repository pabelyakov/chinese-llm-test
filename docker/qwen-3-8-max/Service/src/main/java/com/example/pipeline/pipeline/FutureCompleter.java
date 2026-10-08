package com.example.pipeline.pipeline;

import com.example.pipeline.correlation.CorrelationRegistry;
import com.example.pipeline.domain.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Завершает CompletableFuture контроллера, прочитав итоговый envelope из Redis.
 * Используется и pub/sub-подписчиком, и polling-fallback'ом (идемпотентен).
 */
@Component
public class FutureCompleter {

    private static final Logger log = LoggerFactory.getLogger(FutureCompleter.class);

    private final CorrelationRegistry registry;
    private final RedisResultStore resultStore;
    private final ObjectMapper objectMapper;

    public FutureCompleter(CorrelationRegistry registry,
                           RedisResultStore resultStore,
                           ObjectMapper objectMapper) {
        this.registry = registry;
        this.resultStore = resultStore;
        this.objectMapper = objectMapper;
    }

    /**
     * @return true, если future был завершён этим вызовом.
     */
    public boolean tryComplete(String correlationId) {
        CorrelationRegistry.Entry entry = registry.get(correlationId);
        if (entry == null || entry.future().isDone()) {
            return false;
        }
        Optional<String> json = resultStore.read(correlationId);
        if (json.isEmpty()) {
            return false;
        }
        try {
            Envelope envelope = objectMapper.readValue(json.get(), Envelope.class);
            registry.markStage(correlationId, "completed");
            log.info("[{}] result fetched from Redis, completing future", correlationId);
            return registry.complete(correlationId, envelope);
        } catch (Exception e) {
            log.error("[{}] failed to parse envelope from Redis: {}", correlationId, e.getMessage());
            entry.future().completeExceptionally(e);
            return true;
        }
    }
}
