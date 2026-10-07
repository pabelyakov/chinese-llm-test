package com.example.monolith.api;

import java.util.List;

import com.example.monolith.model.Enrichment;

public record FinalResponse(
        String correlationId,
        String originalMessage,
        String finalMessage,
        List<Enrichment> enrichments,
        String retrievedFromRedisAt) {
}
