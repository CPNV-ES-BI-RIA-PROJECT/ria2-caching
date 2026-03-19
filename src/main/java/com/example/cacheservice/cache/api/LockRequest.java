package com.example.cacheservice.cache.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record LockRequest(
        @NotBlank(message = "must not be blank") String owner,
        @Min(value = 1, message = "must be greater than 0") long leaseMs) {
}
