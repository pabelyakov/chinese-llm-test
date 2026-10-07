package com.example.pipeline.controller;

import com.example.pipeline.model.PipelineMessage;
import com.example.pipeline.model.StartRequest;
import com.example.pipeline.pipeline.PipelineService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/start")
    public ResponseEntity<PipelineMessage> start(@Valid @RequestBody StartRequest request) {
        return ResponseEntity.ok(pipelineService.start(request.message()));
    }
}
