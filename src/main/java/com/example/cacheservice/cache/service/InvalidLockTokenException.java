package com.example.cacheservice.cache.service;

public class InvalidLockTokenException extends RuntimeException {

    public InvalidLockTokenException(String message) {
        super(message);
    }
}
