package com.example.monolith.flow;

public class RedisKeyMissingException extends RuntimeException {

    private final String correlationId;

    public RedisKeyMissingException(String correlationId) {
        super("stored message not found in Redis for correlationId=%s (TTL expired or not written)"
                .formatted(correlationId));
        this.correlationId = correlationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
