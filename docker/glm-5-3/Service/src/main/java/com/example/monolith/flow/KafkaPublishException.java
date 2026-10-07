package com.example.monolith.flow;

public class KafkaPublishException extends RuntimeException {

    private final String correlationId;

    public KafkaPublishException(String correlationId, Throwable cause) {
        super("failed to publish correlationId=%s to Kafka: %s".formatted(correlationId, cause), cause);
        this.correlationId = correlationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
