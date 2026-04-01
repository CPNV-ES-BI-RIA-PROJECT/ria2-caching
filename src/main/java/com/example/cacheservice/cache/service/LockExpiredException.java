package com.example.cacheservice.cache.service;

public class LockExpiredException extends RuntimeException {

    public LockExpiredException(String message) {
        super(message);
    }
}
