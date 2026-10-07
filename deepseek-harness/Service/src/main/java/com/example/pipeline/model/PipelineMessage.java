package com.example.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Message that travels through the whole pipeline. It accumulates a list of
 * {@link Stage} entries, one per external system it has passed through.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineMessage {

    private String id;
    private String message;
    private List<Stage> stages = new ArrayList<>();

    public PipelineMessage() {
    }

    public PipelineMessage(String id, String message) {
        this.id = id;
        this.message = message;
    }

    public void addStage(String name, String status) {
        this.stages.add(new Stage(name, status, Instant.now()));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<Stage> getStages() {
        return stages;
    }

    public void setStages(List<Stage> stages) {
        this.stages = stages;
    }
}
