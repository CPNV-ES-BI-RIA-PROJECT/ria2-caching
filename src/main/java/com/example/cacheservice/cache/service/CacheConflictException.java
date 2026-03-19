package com.example.cacheservice.cache.service;

public class CacheConflictException extends RuntimeException {

    public CacheConflictException(String message) {
        super(message);
    }
}
