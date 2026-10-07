package com.example.pipeline.pipeline;

/**
 * Thrown when a pipeline run does not complete within the configured timeout.
 */
public class PipelineTimeoutException extends RuntimeException {

    public PipelineTimeoutException(String message) {
        super(message);
    }
}
