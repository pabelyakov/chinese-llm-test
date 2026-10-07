package com.example.monolith.flow;

import java.time.Duration;

public class FlowTimeoutException extends RuntimeException {

    private final String correlationId;
    private final Duration timeout;

    public FlowTimeoutException(String correlationId, Duration timeout, Throwable cause) {
        super("flow for correlationId=%s did not complete within %s".formatted(correlationId, timeout), cause);
        this.correlationId = correlationId;
        this.timeout = timeout;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
