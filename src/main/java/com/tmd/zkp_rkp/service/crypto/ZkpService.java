package com.tmd.zkp_rkp.service.crypto;

import com.tmd.zkp_rkp.config.ZkpCryptoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import com.tmd.zkp_rkp.config.ZkpCryptoConfig.SchnorrGroup;
import static com.tmd.zkp_rkp.common.ZkpValue.CHALLENGE_PREFIX;
import static com.tmd.zkp_rkp.common.ZkpValue.CHALLENGE_TTL;

/**
 * @Description ZKP算法实现 - 正确的Schnorr协议实现
 * 协议流程:
 * 1. 客户端生成随机数 r, 计算 R = g^r mod p, 发送 R 给服务器
 * 2. 服务器计算挑战 c = H(R || Y || username), 存储 challenge 并返回 c 给客户端
 * 3. 客户端计算 s = r + c*x mod q, 发送 s 给服务器
 * 4. 服务器验证 g^s == R * Y^c mod p
 * @Author Bluegod
 * @Date 2026/1/27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpService {
    private final SchnorrGroup group;
    private final SecureRandom random;
    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 生成挑战（Challenge Phase）
     * 客户端发送 R = g^r, 服务器计算并返回挑战 c = H(R || Y || username)
     */
    public Mono<Challenge> generateChallenge(String username, BigInteger clientR, BigInteger publicKeyY) {
        // 验证 R 在合法范围内
        if (clientR == null || clientR.compareTo(BigInteger.ONE) <= 0 || clientR.compareTo(group.p()) >= 0) {
            return Mono.error(new IllegalArgumentException("Invalid client R value"));
        }

        String challengeId = UUID.randomUUID().toString();

        // 计算挑战值 c = H(R || Y || username)
        BigInteger c = computeChallenge(clientR, publicKeyY, username);

        // 存储格式: username:clientR:c (都用十六进制存储大数)
        String storedValue = String.format("%s:%s:%s",
                username,
                clientR.toString(16),
                c.toString(16)
        );

        return redisTemplate.opsForValue()
                .set(CHALLENGE_PREFIX + challengeId, storedValue, CHALLENGE_TTL)
                .thenReturn(new Challenge(challengeId, clientR, c, group.p(), group.q(), group.g()));
    }

    /**
     * 根据 challengeId 查找对应的 challenge 数据
     */
    public Mono<ChallengeData> getChallengeData(String challengeId) {
        String key = CHALLENGE_PREFIX + challengeId;

        return redisTemplate.opsForValue().get(key)
                .flatMap(stored -> {
                    String[] parts = stored.split(":");
                    if (parts.length != 3) {
                        return Mono.error(new IllegalStateException("Invalid challenge format"));
                    }

                    String username = parts[0];
                    BigInteger clientR = new BigInteger(parts[1], 16);
                    BigInteger c = new BigInteger(parts[2], 16);

                    return Mono.just(new ChallengeData(username, clientR, c));
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Challenge expired or not found")));
    }

    /**
     * 计算挑战值 c = H(R || Y || username)
     * 使用十六进制字符串进行哈希，确保与客户端一致
     */
    public BigInteger computeChallenge(BigInteger R, BigInteger Y, String username) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 使用十六进制字符串进行哈希，与客户端保持一致
            String rHex = R.toString(16);
            String yHex = Y.toString(16);
            log.debug("Computing challenge with R(hex)={}, Y(hex)={}, username={}", rHex.substring(0, Math.min(32, rHex.length())), yHex.substring(0, Math.min(32, yHex.length())), username);
            digest.update(rHex.getBytes(StandardCharsets.UTF_8));
            digest.update(yHex.getBytes(StandardCharsets.UTF_8));
            digest.update(username.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            BigInteger c = new BigInteger(1, hash).mod(group.q());
            log.debug("Computed c={}", c.toString(16).substring(0, Math.min(32, c.toString(16).length())));
            return c;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 验证零知识证明（Verification Phase）
     * 验证方程: g^s == R * Y^c (mod p)
     * 其中 c = H(R || Y || username) - 使用存储的c值，不再重新计算
     */
    public Mono<Boolean> verifyProof(String challengeId, SchnorrProof proof, BigInteger publicKeyY) {
        return redisTemplate.opsForValue().get(CHALLENGE_PREFIX + challengeId)
                .flatMap(stored -> {
                    String[] parts = stored.split(":");
                    if (parts.length != 3) {
                        return Mono.error(new IllegalStateException("Invalid challenge format"));
                    }

                    String storedUsername = parts[0];
                    BigInteger R = new BigInteger(parts[1], 16);
                    BigInteger c = new BigInteger(parts[2], 16);

                    // 验证 clientR 是否匹配服务器存储的 R（防止篡改）
                    if (!R.equals(proof.clientR())) {
                        log.warn("Client R mismatch for challenge {}", challengeId);
                        return Mono.just(false);
                    }

                    // 验证用户名匹配
                    if (!storedUsername.equals(proof.username())) {
                        log.warn("Username mismatch for challenge {}", challengeId);
                        return Mono.just(false);
                    }

                    // 使用存储的 c 值进行验证（不再重新计算，确保一致性）

                    // Schnorr 验证方程: g^s == R * Y^c (mod p)
                    BigInteger leftSide = group.g().modPow(proof.s(), group.p());
                    BigInteger Yc = publicKeyY.modPow(c, group.p());
                    BigInteger rightSide = proof.clientR().multiply(Yc).mod(group.p());

                    boolean valid = leftSide.equals(rightSide);

                    // Debug logging
                    log.debug("ZKP Verification Debug for user: {}", storedUsername);
                    log.debug("  R (stored): {}", R.toString(16).substring(0, 32) + "...");
                    log.debug("  Y: {}", publicKeyY.toString(16).substring(0, 32) + "...");
                    log.debug("  c (stored): {}", c.toString(16).substring(0, 32) + "...");
                    log.debug("  s: {}", proof.s().toString(16).substring(0, 32) + "...");
                    log.debug("  leftSide (g^s): {}", leftSide.toString(16).substring(0, 32) + "...");
                    log.debug("  rightSide (R*Y^c): {}", rightSide.toString(16).substring(0, 32) + "...");
                    log.debug("  valid: {}", valid);

                    if (valid) {
                        log.debug("ZKP verified for user: {}", storedUsername);
                        // 验证成功后删除挑战（防重放）
                        return redisTemplate.delete(CHALLENGE_PREFIX + challengeId)
                                .thenReturn(true);
                    } else {
                        log.warn("ZKP verification failed for user: {}", storedUsername);
                        return Mono.just(false);
                    }
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Challenge expired or not found")));
    }

    // 记录定义
    public record Challenge(String challengeId, BigInteger clientR, BigInteger c, BigInteger p, BigInteger q, BigInteger g) {}
    public record SchnorrProof(BigInteger s, BigInteger clientR, String username) {}
    public record ChallengeData(String username, BigInteger clientR, BigInteger c) {}
}
