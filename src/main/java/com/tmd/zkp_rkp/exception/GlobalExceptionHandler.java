package com.tmd.zkp_rkp.exception;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AuthDTOs.ErrorResponse(
                        "VALIDATION_ERROR",
                        errors,
                        Instant.now().getEpochSecond()
                ));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleSecurityException(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthDTOs.ErrorResponse(
                        "AUTH_FAILED",
                        "Authentication failed",
                        Instant.now().getEpochSecond()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AuthDTOs.ErrorResponse(
                        "BAD_REQUEST",
                        ex.getMessage(),
                        Instant.now().getEpochSecond()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthDTOs.ErrorResponse(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        Instant.now().getEpochSecond()
                ));
    }
}
