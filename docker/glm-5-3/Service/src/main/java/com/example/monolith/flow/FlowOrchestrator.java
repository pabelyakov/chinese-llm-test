package com.example.monolith.flow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FlowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlowOrchestrator.class);

    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<String> register(String correlationId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> previous = pending.put(correlationId, future);
        if (previous != null && !previous.isDone()) {
            previous.completeExceptionally(
                    new IllegalStateException("correlationId " + correlationId + " was re-registered"));
        }
        return future;
    }

    public boolean complete(String correlationId, String payload) {
        CompletableFuture<String> future = pending.remove(correlationId);
        if (future == null) {
            log.warn("no pending HTTP request for correlationId={}, result dropped", correlationId);
            return false;
        }
        return future.complete(payload);
    }

    public void unregister(String correlationId, CompletableFuture<String> future) {
        boolean removed = pending.remove(correlationId, future);
        log.debug("unregister correlationId={} removed={}", correlationId, removed);
    }

    public int pendingCount() {
        return pending.size();
    }
}
