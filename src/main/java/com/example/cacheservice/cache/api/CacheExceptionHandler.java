package com.example.cacheservice.cache.api;

import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.cacheservice.cache.service.CacheConflictException;
import com.example.cacheservice.cache.service.InvalidLockTokenException;
import com.example.cacheservice.cache.service.LockExpiredException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class CacheExceptionHandler {

    @ExceptionHandler(CacheConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(CacheConflictException exception, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(LockExpiredException.class)
    public ResponseEntity<ErrorResponse> handleLockExpired(LockExpiredException exception, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidLockTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidLockTokenException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadablePayload(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Request body is malformed or missing required fields.",
                request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message, String path) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(body);
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
