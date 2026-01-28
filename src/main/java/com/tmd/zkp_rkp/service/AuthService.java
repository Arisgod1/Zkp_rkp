package com.tmd.zkp_rkp.service;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import com.tmd.zkp_rkp.entity.UserCredentials;
import com.tmd.zkp_rkp.repository.UserCredentialsRepository;
import com.tmd.zkp_rkp.service.crypto.ZkpService;
import com.tmd.zkp_rkp.service.kafka.AuthEventPublisher;
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
    /**
     * 用户注册 - 存储公钥
     */
    @Transactional
    public Mono<Void> register(AuthDTOs.RegisterRequest req) {
        return Mono.fromCallable(() -> userRepo.existsByUsername(req.username()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new IllegalArgumentException("Username already exists"));
                    }

                    // 验证公钥格式
                    BigInteger Y;
                    try {
                        Y = new BigInteger(req.publicKeyY(), 16);
                        // 可选：验证 Y 在合法范围内
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
                                // 发送 Kafka 事件
                                return eventPublisher.publishUserRegistered(
                                        saved.getId(),
                                        saved.getUsername(),
                                        LocalDateTime.now()
                                );
                            });
                });
    }

    /**
     * 生成登录挑战
     */
    public Mono<AuthDTOs.ChallengeResponse> createChallenge(AuthDTOs.ChallengeRequest req) {
        return Mono.fromCallable(() -> userRepo.findByUsername(req.username()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optUser -> {
                    if (optUser.isEmpty()) {
                        // 安全考虑：即使用户不存在也生成假挑战，防止用户名枚举攻击
                        return zkpService.generateChallenge(req.username())
                                .map(AuthDTOs.ChallengeResponse::fromServiceRecord)
                                .onErrorResume(e -> {
                                    // 记录但不暴露
                                    log.warn("Challenge generation for non-existent user: {}", req.username());
                                    return Mono.error(new IllegalArgumentException("User not found"));
                                });
                    }
                    return zkpService.generateChallenge(req.username())
                            .map(AuthDTOs.ChallengeResponse::fromServiceRecord);
                });
    }

    /**
     * 验证 ZKP 并登录
     */
    @Transactional
    public Mono<AuthDTOs.AuthResponse> verifyAndLogin(AuthDTOs.VerifyRequest req) {
        // 先根据 challengeId 解析出 username（从 Redis 中）
        // 注意：这里简化处理，实际需要优化流程
        return Mono.fromCallable(() -> userRepo.findByUsername(
                        extractUsernameFromChallenge(req.challengeId()))) // 需要修改 ZkpService 暴露查询方法
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optUser -> {
                    if (optUser.isEmpty()) {
                        return Mono.error(new IllegalArgumentException("Invalid challenge"));
                    }

                    UserCredentials user = optUser.get();
                    BigInteger Y = user.getPublicKeyYAsBigInteger();

                    return zkpService.verifyProof(req.challengeId(), req.toProof(), Y)
                            .flatMap(valid -> {
                                if (!valid) {
                                    return eventPublisher.publishLoginFailed(user.getUsername(), "Invalid proof")
                                            .then(Mono.error(new SecurityException("Authentication failed")));
                                }

                                // 更新最后登录时间
                                return Mono.fromRunnable(() ->
                                                userRepo.updateLastLoginTime(user.getUsername(), LocalDateTime.now())
                                        ).subscribeOn(Schedulers.boundedElastic())
                                        .then(eventPublisher.publishLoginSuccess(user.getUsername()))
                                        .thenReturn(generateToken(user));
                            });
                });
    }

    private AuthDTOs.AuthResponse generateToken(UserCredentials user) {
        // TODO: 实际项目中集成 JWT,待会再整
        String token = UUID.randomUUID().toString(); // 临时模拟
        return new AuthDTOs.AuthResponse(
                token,
                "Bearer",
                user.getUsername(),
                Duration.ofHours(24).getSeconds()
        );
    }

    // 临时方案：需要从 Redis 获取 challenge 中的 username，实际项目中应该重构 ZkpService 暴露一个查询方法hh
    private String extractUsernameFromChallenge(String challengeId) {
        // 这里需要通过 ZkpService 查询，代码略
        return "username";
    }
}
