package com.tmd.zkp_rkp.service;

import com.tmd.zkp_rkp.config.ZkpCryptoConfig;
import com.tmd.zkp_rkp.dto.AuthDTOs;
import com.tmd.zkp_rkp.entity.UserCredentials;
import com.tmd.zkp_rkp.repository.UserCredentialsRepository;
import com.tmd.zkp_rkp.service.crypto.ZkpService;
import com.tmd.zkp_rkp.service.kafka.AuthEventPublisher;
import com.tmd.zkp_rkp.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;

import static com.tmd.zkp_rkp.common.ZkpValue.P;

/**
 * @Description 认证服务 - 实现ZKP零知识证明登录流程
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
    @Autowired
    private ZkpCryptoConfig cryptoConfig;
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
                        // 验证 Y 在 [2, p-2] 范围内
                        if (Y.compareTo(P.subtract(BigInteger.ONE)) >= 0) {
                            return Mono.error(new IllegalArgumentException("Public key too large"));
                        }
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

    /**
     * 创建挑战 - 正确的Schnorr协议流程
     * 1. 客户端生成随机数 r, 计算 R = g^r mod p
     * 2. 客户端发送 username 和 R 给服务器
     * 3. 服务器查询用户公钥 Y
     * 4. 服务器计算挑战 c = H(R || Y || username)
     * 5. 服务器存储 challenge 并返回 c 给客户端
     */
    public Mono<AuthDTOs.ChallengeResponse> createChallenge(AuthDTOs.ChallengeRequest req) {
        BigInteger clientR;
        try {
            clientR = new BigInteger(req.clientR(), 16);
        } catch (NumberFormatException e) {
            return Mono.error(new IllegalArgumentException("Invalid R format"));
        }

        return Mono.fromCallable(() -> userRepo.findByUsername(req.username()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optUser -> {
                    if (optUser.isEmpty()) {
                        // 用户不存在，生成假挑战（防枚举攻击）
                        // 使用随机公钥计算假挑战，保持相同的响应时间
                        BigInteger fakeY = new BigInteger(1536, new SecureRandom())
                                .mod(P.subtract(BigInteger.TWO))
                                .add(BigInteger.TWO);
                        return zkpService.generateChallenge(req.username(), clientR, fakeY)
                                .map(challenge -> AuthDTOs.ChallengeResponse.fromServiceRecord(challenge));
                    }

                    UserCredentials user = optUser.get();
                    BigInteger Y = user.getPublicKeyYAsBigInteger();

                    return zkpService.generateChallenge(req.username(), clientR, Y)
                            .map(challenge -> {
                                log.debug("Challenge phase for user {}: R={}, Y={}, c={}",
                                        req.username(),
                                        challenge.clientR().toString(16).substring(0, Math.min(32, challenge.clientR().toString(16).length())),
                                        Y.toString(16).substring(0, Math.min(32, Y.toString(16).length())),
                                        challenge.c().toString(16).substring(0, Math.min(32, challenge.c().toString(16).length())));
                                return AuthDTOs.ChallengeResponse.fromServiceRecord(challenge);
                            });
                })
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
                                            // 异步更新登录时间，不阻塞主流程
                                            Mono.fromRunnable(() -> {
                                                try {
                                                    user.setLastLoginAt(LocalDateTime.now());
                                                    userRepo.save(user);
                                                } catch (Exception e) {
                                                    log.warn("Failed to update last login time: {}", e.getMessage());
                                                }
                                            }).subscribeOn(Schedulers.boundedElastic()).subscribe();

                                            return eventPublisher.publishLoginSuccess(user.getUsername())
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
