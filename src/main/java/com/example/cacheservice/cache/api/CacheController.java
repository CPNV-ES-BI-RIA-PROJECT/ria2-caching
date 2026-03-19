package com.example.cacheservice.cache.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.CacheState;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.service.CacheService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/cache")
public class CacheController {

    private final CacheService cacheService;

    public CacheController(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/{namespace}/{key}")
    public ResponseEntity<CacheResponse> getCache(
            @PathVariable String namespace,
            @PathVariable String key) {
        CacheLookup lookup = cacheService.lookup(namespace, key);

        if (lookup.state() == CacheState.MISS) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CacheResponse.miss(namespace, key));
        }

        if (lookup.state() == CacheState.COMPUTING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(CacheResponse.from(lookup.entry()));
        }

        return ResponseEntity.ok(CacheResponse.from(lookup.entry()));
    }

    @PostMapping("/{namespace}/{key}/lock")
    public LockResponse lock(
            @PathVariable String namespace,
            @PathVariable String key,
            @Valid @RequestBody LockRequest request) {
        LockAcquisition acquisition = cacheService.acquireLock(namespace, key, request.owner(), request.leaseMs());
        return LockResponse.from(acquisition);
    }

    @PostMapping("/{namespace}/{key}/publish")
    public CacheResponse publish(
            @PathVariable String namespace,
            @PathVariable String key,
            @Valid @RequestBody PublishRequest request) {
        return CacheResponse.from(
                cacheService.publish(namespace, key, request.token(), request.artifactUri(), request.metadata(),
                        request.ttlSeconds()));
    }

    @DeleteMapping("/{namespace}/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable String namespace,
            @PathVariable String key) {
        cacheService.delete(namespace, key);
        return ResponseEntity.noContent().build();
    }
}
