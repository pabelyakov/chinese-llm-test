package com.example.monolith.api;

import java.time.Instant;

public record ErrorResponse(
        String timestamp,
        int status,
        String error,
        String correlationId,
        String detail) {

    public static ErrorResponse of(int status, String error, String correlationId, String detail) {
        return new ErrorResponse(Instant.now().toString(), status, error, correlationId, detail);
    }
}
