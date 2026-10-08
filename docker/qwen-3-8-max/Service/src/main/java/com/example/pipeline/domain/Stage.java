package com.example.pipeline.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public class Stage {

    private String stage;
    private String at;
    private Map<String, Object> data = new LinkedHashMap<>();

    public Stage() {
    }

    public Stage(String stage, String at, Map<String, Object> data) {
        this.stage = stage;
        this.at = at;
        this.data = data == null ? new LinkedHashMap<>() : data;
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
