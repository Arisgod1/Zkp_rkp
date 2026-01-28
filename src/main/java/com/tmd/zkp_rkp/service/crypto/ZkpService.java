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
import java.util.UUID;

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
    private final ZkpCryptoConfig.SchnorrGroup group;
    private final SecureRandom random;
    private final ReactiveStringRedisTemplate redisTemplate;

    /*
     * 为用户生成登录挑战
     * @param username 用户名
     * @return 包含 challenge_id 和 R 的 Challenge 对象
     * */
    public Mono<Challenge> generateChallenge(String username) {
        //生成随机数 r ∈ [1, q-1]
        BigInteger r = new BigInteger(group.q().bitLength(), random)
                .mod(group.q().subtract(BigInteger.ONE))
                .add(BigInteger.ONE);

        // 计算R = g^r mod p
        BigInteger R = group.g().modPow(r, group.p());

        String challengeId = UUID.randomUUID().toString();

        //将挑战存入redis:{challenge_id -> "username:r:R"}
        String storedValue = String.format("%s:%s:%s", username, r.toString(16), R.toString(16));

        return redisTemplate.opsForValue()
                .set(CHALLENGE_PREFIX + challengeId, storedValue, CHALLENGE_TTL)
                .thenReturn(new Challenge(challengeId, R, group.p(), group.q(), group.g()));
    }

    /**
     * 验证零知识证明（Verification Phase）
     *
     * @param challengeId 挑战ID
     * @param proof       客户端提交的证明 (s, clientR)
     * @param publicKeyY  用户的公钥 Y = g^x mod p
     * @return 验证结果
     */
    public Mono<Boolean> verifyProof(String challengeId, SchnorrProof proof, BigInteger publicKeyY) {
        String key = CHALLENGE_PREFIX + challengeId;
        return redisTemplate.opsForValue().getAndDelete(key)
                .flatMap(
                        stored -> {
                            //解析存储的挑战: username:r:R
                            String[] parts = stored.split(":");
                            if (parts.length != 3) {
                                return Mono.error(new IllegalStateException("Invalid challenge format"));
                            }

                            String storedUsername = parts[0];
                            BigInteger r = new BigInteger(parts[1], 16);
                            BigInteger R = new BigInteger(parts[2], 16);

                            //验证clientR是否匹配(检查篡改hh)
                            if (!R.equals(proof.clientR())) {
                                log.warn("Client R mismatch for challenge {}", challengeId);
                                return Mono.just(false);
                            }

                            //重新计算 challenge c = H(R || Y || username)
                            BigInteger c = hashChallenge(proof.clientR(), publicKeyY, storedUsername);

                            //Schnorr 验证方程: g^s == R * Y^c (mod p)
                            // 左侧: g^s mod p
                            BigInteger leftSide = group.g().modPow(proof.s(), group.p());

                            // 右侧: (R * Y^c) mod p
                            BigInteger Yc = publicKeyY.modPow(c, group.p());
                            BigInteger rightSide = proof.clientR().multiply(Yc).mod(group.p());

                            boolean valid = leftSide.equals(rightSide);

                            if (valid) {
                                log.debug("ZKP verified successfully for user: {}", storedUsername);
                            } else {
                                log.warn("ZKP verification failed for user: {}", storedUsername);
                            }

                            return Mono.just(valid);
                        })
                .switchIfEmpty(Mono.error(new IllegalStateException("Challenge expired or not found")));
    }

    /*
     *哈希函数 H : 生成挑战值c
     * 使用SHA-256 近似实现Fiat-Shamir 启发式
     * */
    private BigInteger hashChallenge(BigInteger R, BigInteger Y, String username){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(R.toByteArray());
            digest.update(Y.toByteArray());
            digest.update(username.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return new BigInteger(1, hash).mod(group.q());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available",e);
        }
    }
    public record Challenge(String challengeId, BigInteger R, BigInteger p, BigInteger q, BigInteger g) {
    }

    public record SchnorrProof(BigInteger s, BigInteger clientR) {
    }
}
