package com.example.pipeline.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StartRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 10_000, message = "message must not exceed 10000 characters")
        String message
) {
}
