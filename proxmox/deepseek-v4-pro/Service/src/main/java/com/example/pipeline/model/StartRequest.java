package com.example.pipeline.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /start}.
 */
public record StartRequest(@NotBlank(message = "message must not be blank") String message) {
}
