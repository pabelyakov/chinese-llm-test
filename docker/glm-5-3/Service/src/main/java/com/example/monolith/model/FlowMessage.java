package com.example.monolith.model;

import java.util.ArrayList;
import java.util.List;

public record FlowMessage(String correlationId, String originalMessage, List<Enrichment> enrichments) {

    public FlowMessage {
        enrichments = enrichments == null ? List.of() : List.copyOf(enrichments);
    }

    public static FlowMessage initial(String correlationId, String originalMessage) {
        return new FlowMessage(correlationId, originalMessage, List.of());
    }

    public FlowMessage withEnrichment(Enrichment enrichment) {
        List<Enrichment> updated = new ArrayList<>(enrichments);
        updated.add(enrichment);
        return new FlowMessage(correlationId, originalMessage, List.copyOf(updated));
    }
}
