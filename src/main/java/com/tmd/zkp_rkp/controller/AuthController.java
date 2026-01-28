package com.tmd.zkp_rkp.controller;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import com.tmd.zkp_rkp.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public Mono<ResponseEntity<Void>> register(@Valid @RequestBody AuthDTOs.RegisterRequest req) {
        return authService.register(req)
                .then(Mono.just(ResponseEntity.status(HttpStatus.CREATED).<Void>build()))
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).<Void>build())
                );
    }

    @PostMapping("/challenge")
    public Mono<ResponseEntity<AuthDTOs.ChallengeResponse>> getChallenge(@Valid @RequestBody AuthDTOs.ChallengeRequest req) {
        return authService.createChallenge(req)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Challenge generation failed", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    @PostMapping("/verify")
    public Mono<ResponseEntity<AuthDTOs.AuthResponse>> verify(@Valid @RequestBody AuthDTOs.VerifyRequest req) {
        return authService.verifyAndLogin(req)
                .map(ResponseEntity::ok)
                .onErrorResume(SecurityException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
                )
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
                );
    }

    // 全局错误处理
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthDTOs.ErrorResponse> handleException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthDTOs.ErrorResponse(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        Instant.now().getEpochSecond()
                ));
    }
}
