package com.tmd.zkp_rkp.exception;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import java.time.Instant;

/**
 * 全局异常处理 - WebFlux版本
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleServerWebInputException(ServerWebInputException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AuthDTOs.ErrorResponse(
                        "BAD_REQUEST",
                        "Invalid request: " + ex.getReason(),
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
