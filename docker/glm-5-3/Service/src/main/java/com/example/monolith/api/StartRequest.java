package com.example.monolith.api;

import jakarta.validation.constraints.NotBlank;

public record StartRequest(@NotBlank(message = "message must not be blank") String message) {
}
