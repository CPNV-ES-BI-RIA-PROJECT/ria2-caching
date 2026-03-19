package com.example.cacheservice.cache.api;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PublishRequest(
        @NotBlank(message = "must not be blank") String token,
        @NotBlank(message = "must not be blank") String artifactUri,
        JsonNode metadata,
        @NotNull(message = "must not be null") @Min(value = 1, message = "must be greater than 0") Long ttlSeconds) {
}
