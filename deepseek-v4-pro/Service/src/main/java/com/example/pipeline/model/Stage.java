package com.example.pipeline.model;

import java.time.Instant;

/**
 * One enrichment stage of the pipeline. Immutable value object, serialized by Jackson.
 */
public record Stage(String name, String status, Instant processedAt) {
}
