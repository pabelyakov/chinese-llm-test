package com.example.monolith.flow;

public class FlowFailedException extends RuntimeException {

    private final String correlationId;

    public FlowFailedException(String correlationId, Throwable cause) {
        super("flow for correlationId=%s failed: %s".formatted(correlationId, cause), cause);
        this.correlationId = correlationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
