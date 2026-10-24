package com.example.wallet.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

  public record ApiError(String code,String message,String request_id) {}

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ApiError> domain(DomainException e,HttpServletRequest r) {
    return ResponseEntity.status(e.status()).body(new ApiError(e.code(),e.getMessage(),UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiError> header(Exception e,HttpServletRequest r) {
    return ResponseEntity.badRequest().body(new ApiError("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key header is required.",UUID.randomUUID().toString()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> other(Exception e,HttpServletRequest r) {
    return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR","An unexpected error occurred.",UUID.randomUUID().toString()));
  }
}
