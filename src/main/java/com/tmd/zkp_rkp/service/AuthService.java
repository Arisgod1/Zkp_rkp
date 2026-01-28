package com.tmd.zkp_rkp.service;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import com.tmd.zkp_rkp.entity.UserCredentials;
import com.tmd.zkp_rkp.repository.UserCredentialsRepository;
import com.tmd.zkp_rkp.service.crypto.ZkpService;
import com.tmd.zkp_rkp.service.kafka.AuthEventPublisher;
import com.tmd.zkp_rkp.util.JwtUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserCredentialsRepository userRepo;
    private final ZkpService zkpService;
    private final AuthEventPublisher eventPublisher;
    private final JwtUtil jwtUtil;

    @Transactional
    public Mono<Void> register(AuthDTOs.RegisterRequest req) {
        return Mono.fromCallable(() -> userRepo.existsByUsername(req.username()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new IllegalArgumentException("Username already exists"));
                    }

                    // 验证公钥格式（确保是合法的大数）
                    BigInteger Y;
                    try {
                        Y = new BigInteger(req.publicKeyY(), 16);
                        // 可选：验证 Y 在 [2, p-2] 范围内
                        if (Y.compareTo(BigInteger.TWO) < 0) {
                            return Mono.error(new IllegalArgumentException("Public key too small"));
                        }
                    } catch (NumberFormatException e) {
                        return Mono.error(new IllegalArgumentException("Invalid public key format"));
                    }

                    UserCredentials user = UserCredentials.builder()
                            .username(req.username())
                            .publicKeyY(req.publicKeyY())
                            .salt(req.salt())
                            .build();

                    return Mono.fromCallable(() -> userRepo.save(user))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(saved -> {
                                log.info("User registered: {}", req.username());
                                return eventPublisher.publishUserRegistered(
                                        saved.getId(),
                                        saved.getUsername(),
                                        LocalDateTime.now()
                                );
                            });
                });
    }

    public Mono<AuthDTOs.ChallengeResponse> createChallenge(AuthDTOs.ChallengeRequest req) {
        // 即使不存在用户名也生成假挑战（防枚举攻击）
        return zkpService.generateChallenge(req.username())
                .map(AuthDTOs.ChallengeResponse::fromServiceRecord)
                .onErrorResume(e -> {
                    log.error("Challenge generation failed", e);
                    return Mono.error(new RuntimeException("Service temporarily unavailable"));
                });
    }

    @Transactional
    public Mono<AuthDTOs.AuthResponse> verifyAndLogin(AuthDTOs.VerifyRequest req) {
        // Step 1: 从 challengeId 获取 username 和验证数据
        return zkpService.getChallengeData(req.challengeId())
                .flatMap(challengeData -> {
                    String username = challengeData.username();

                    // Step 2: 查询用户公钥
                    return Mono.fromCallable(() -> userRepo.findByUsername(username))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(optUser -> {
                                if (optUser.isEmpty()) {
                                    // 用户不存在（可能是之前生成的假挑战）
                                    return eventPublisher.publishLoginFailed(username, "User not found")
                                            .then(Mono.error(new SecurityException("Authentication failed")));
                                }

                                UserCredentials user = optUser.get();
                                BigInteger Y = user.getPublicKeyYAsBigInteger();

                                // Step 3: 验证 ZKP
                                return zkpService.verifyProof(req.challengeId(), req.toProof(), Y)
                                        .flatMap(valid -> {
                                            if (!valid) {
                                                return eventPublisher.publishLoginFailed(user.getUsername(), "Invalid proof")
                                                        .then(Mono.error(new SecurityException("Authentication failed")));
                                            }

                                            // Step 4: 更新登录时间并生成 Token
                                            return Mono.fromRunnable(() ->
                                                            userRepo.updateLastLoginTime(user.getUsername(), LocalDateTime.now())
                                                    ).subscribeOn(Schedulers.boundedElastic())
                                                    .then(eventPublisher.publishLoginSuccess(user.getUsername()))
                                                    .thenReturn(generateAuthResponse(user));
                                        });
                            });
                });
    }

    private AuthDTOs.AuthResponse generateAuthResponse(UserCredentials user) {
        String token = jwtUtil.generateToken(user.getUsername());
        long expiresIn = 86400; // 24小时，与JwtUtil配置同步

        return new AuthDTOs.AuthResponse(
                token,
                "Bearer",
                user.getUsername(),
                expiresIn
        );
    }
}
