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
 * @Description ZKP算法实现
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
     * 为用户生成登录挑战（Challenge Phase）
     */
    public Mono<Challenge> generateChallenge(String username) {
        // 生成随机数 r ∈ [1, q-1]
        BigInteger r;
        do {
            r = new BigInteger(group.q().bitLength(), random);
        } while (r.compareTo(group.q()) >= 0 || r.equals(BigInteger.ZERO));

        // 计算 R = g^r mod p
        BigInteger R = group.g().modPow(r, group.p());

        String challengeId = UUID.randomUUID().toString();

        // 存储格式: username:r:R (都用十六进制存储大数)
        String storedValue = String.format("%s:%s:%s",
                username,
                r.toString(16),
                R.toString(16)
        );

        return redisTemplate.opsForValue()
                .set(CHALLENGE_PREFIX + challengeId, storedValue, CHALLENGE_TTL)
                .thenReturn(new Challenge(challengeId, R, group.p(), group.q(), group.g()));
    }

    /**
     * 根据 challengeId 查找对应的 username（用于后续查询用户公钥）
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
                    BigInteger r = new BigInteger(parts[1], 16);
                    BigInteger R = new BigInteger(parts[2], 16);

                    return Mono.just(new ChallengeData(username, r, R));
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Challenge expired or not found")));
    }

    /**
     * 验证零知识证明（Verification Phase）
     */
    public Mono<Boolean> verifyProof(String challengeId, SchnorrProof proof, BigInteger publicKeyY) {
        return redisTemplate.opsForValue().get(CHALLENGE_PREFIX + challengeId)
                .flatMap(stored -> {
                    String[] parts = stored.split(":");
                    if (parts.length != 3) {
                        return Mono.error(new IllegalStateException("Invalid challenge format"));
                    }

                    String storedUsername = parts[0];
                    BigInteger r = new BigInteger(parts[1], 16);
                    BigInteger R = new BigInteger(parts[2], 16);

                    // 验证 clientR 是否匹配服务器存储的 R（防止篡改）
                    if (!R.equals(proof.clientR())) {
                        log.warn("Client R mismatch for challenge {}", challengeId);
                        return Mono.just(false);
                    }

                    // 计算 challenge c = H(R || Y || username)
                    BigInteger c = hashChallenge(proof.clientR(), publicKeyY, storedUsername);

                    // Schnorr 验证方程: g^s == R * Y^c (mod p)
                    BigInteger leftSide = group.g().modPow(proof.s(), group.p());
                    BigInteger Yc = publicKeyY.modPow(c, group.p());
                    BigInteger rightSide = proof.clientR().multiply(Yc).mod(group.p());

                    boolean valid = leftSide.equals(rightSide);

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

    private BigInteger hashChallenge(BigInteger R, BigInteger Y, String username) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(R.toByteArray());
            digest.update(Y.toByteArray());
            digest.update(username.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return new BigInteger(1, hash).mod(group.q());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // 记录定义
    public record Challenge(String challengeId, BigInteger R, BigInteger p, BigInteger q, BigInteger g) {}
    public record SchnorrProof(BigInteger s, BigInteger clientR) {}
    public record ChallengeData(String username, BigInteger r, BigInteger R) {}
}
