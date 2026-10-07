package com.example.pipeline.pipeline;

import com.example.pipeline.model.PipelineMessage;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates in-flight pipeline runs by message id so that the synchronous HTTP
 * controller can block until the asynchronous listeners have completed.
 */
@Component
public class CorrelationRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<PipelineMessage>> futures =
            new ConcurrentHashMap<>();

    public CompletableFuture<PipelineMessage> register(String id) {
        CompletableFuture<PipelineMessage> future = new CompletableFuture<>();
        futures.put(id, future);
        return future;
    }

    public void complete(String id, PipelineMessage message) {
        CompletableFuture<PipelineMessage> future = futures.remove(id);
        if (future != null) {
            future.complete(message);
        }
    }

    public void remove(String id) {
        futures.remove(id);
    }
}
