package com.example.pipeline.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Envelope {

    private String correlationId;
    private String originalMessage;
    private String createdAt;
    private List<Stage> stages = new CopyOnWriteArrayList<>();

    public Envelope() {
    }

    public Envelope(String correlationId, String originalMessage, String createdAt) {
        this.correlationId = correlationId;
        this.originalMessage = originalMessage;
        this.createdAt = createdAt;
    }

    public void addStage(Stage stage) {
        stages.add(stage);
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getOriginalMessage() { return originalMessage; }
    public void setOriginalMessage(String originalMessage) { this.originalMessage = originalMessage; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) {
        this.stages = new CopyOnWriteArrayList<>(stages);
    }
}
