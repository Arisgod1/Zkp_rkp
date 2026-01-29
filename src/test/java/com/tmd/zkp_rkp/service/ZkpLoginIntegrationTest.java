package com.tmd.zkp_rkp.service;

import com.tmd.zkp_rkp.dto.AuthDTOs;
import com.tmd.zkp_rkp.entity.UserCredentials;
import com.tmd.zkp_rkp.repository.UserCredentialsRepository;
import com.tmd.zkp_rkp.config.ZkpCryptoConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZKP登录功能完整集成测试
 * 测试场景包括：
 * 1. 成功登录（正确的ZKP证明）
 * 2. 无效证明登录
 * 3. 重放攻击防护
 * 4. 边界条件测试
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class ZkpLoginIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserCredentialsRepository userRepo;

    @Autowired
    private ZkpCryptoConfig.SchnorrGroup group;

    private SecureRandom random;

    @BeforeEach
    void setUp() {
        random = new SecureRandom();
    }

    /**
     * 生成随机十六进制字符串
     */
    private String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%x", random.nextInt(16)));
        }
        return sb.toString();
    }

    /**
     * 测试场景1: 成功登录（正确的ZKP证明）
     */
    @Test
    @DisplayName("场景1: 成功登录 - 正确的ZKP证明")
    void testSuccessfulLogin() {
        String username = "testuser_" + generateRandomHex(8);

        // 1. 生成客户端密钥对
        BigInteger x = new BigInteger(256, random).mod(group.q()); // 私钥
        BigInteger Y = group.g().modPow(x, group.p()); // 公钥 Y = g^x mod p
        String Y_hex = Y.toString(16);

        log.info("生成密钥对 - 用户名: {}, 公钥Y: {}...", username, Y_hex.substring(0, 32));

        // 2. 注册用户
        AuthDTOs.RegisterRequest registerReq = new AuthDTOs.RegisterRequest(
                username, Y_hex, generateRandomHex(16)
        );

        StepVerifier.create(authService.register(registerReq))
                .verifyComplete();

        log.info("用户注册成功: {}", username);

        // 3. 生成随机数r，计算R = g^r mod p
        BigInteger r = new BigInteger(256, random).mod(group.q());
        BigInteger R = group.g().modPow(r, group.p());
        String R_hex = R.toString(16);

        log.info("生成R值: {}...", R_hex.substring(0, 32));

        // 4. 请求挑战
        AuthDTOs.ChallengeRequest challengeReq = new AuthDTOs.ChallengeRequest(username, R_hex);

        AuthDTOs.ChallengeResponse challengeResp = authService.createChallenge(challengeReq).block();
        assertThat(challengeResp).isNotNull();
        assertThat(challengeResp.challengeId()).isNotNull();
        assertThat(challengeResp.c()).isNotNull();

        String challengeId = challengeResp.challengeId();
        BigInteger c = new BigInteger(challengeResp.c(), 16);
        
        // 等待Redis写入完成
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("获取挑战 - ID: {}, c: {}...", challengeId, challengeResp.c().substring(0, 32));

        // 5. 计算证明 s = r + c*x mod q
        BigInteger s = r.add(c.multiply(x)).mod(group.q());
        String s_hex = s.toString(16);

        log.info("计算证明s: {}...", s_hex.substring(0, 32));

        // 本地验证
        BigInteger leftSide = group.g().modPow(s, group.p());
        BigInteger Yc = Y.modPow(c, group.p());
        BigInteger rightSide = R.multiply(Yc).mod(group.p());
        boolean localValid = leftSide.equals(rightSide);

        log.info("本地验证结果: {}", localValid);
        assertThat(localValid).isTrue();

        // 6. 发送验证请求
        AuthDTOs.VerifyRequest verifyReq = new AuthDTOs.VerifyRequest(
                username, challengeId, s_hex, R_hex
        );

        AuthDTOs.AuthResponse authResp = authService.verifyAndLogin(verifyReq).block();

        // 验证结果
        assertThat(authResp).isNotNull();
        assertThat(authResp.token()).isNotNull();
        assertThat(authResp.type()).isEqualTo("Bearer");
        assertThat(authResp.username()).isEqualTo(username);
        assertThat(authResp.expiresIn()).isGreaterThan(0);

        log.info("登录成功! Token: {}...", authResp.token().substring(0, 40));
    }

    /**
     * 测试场景2: 无效证明登录
     */
    @Test
    @DisplayName("场景2: 无效证明 - 错误的s值")
    void testInvalidProof() {
        String username = "testuser_" + generateRandomHex(8);

        // 注册用户
        BigInteger x = new BigInteger(256, random).mod(group.q());
        BigInteger Y = group.g().modPow(x, group.p());
        String Y_hex = Y.toString(16);

        authService.register(new AuthDTOs.RegisterRequest(username, Y_hex, generateRandomHex(16))).block();

        // 获取挑战
        BigInteger r = new BigInteger(256, random).mod(group.q());
        BigInteger R = group.g().modPow(r, group.p());
        String R_hex = R.toString(16);

        AuthDTOs.ChallengeResponse challengeResp = authService.createChallenge(
                new AuthDTOs.ChallengeRequest(username, R_hex)
        ).block();

        String challengeId = challengeResp.challengeId();

        // 使用错误的s值（随机生成）
        BigInteger s_wrong = new BigInteger(256, random).mod(group.q());
        String s_wrong_hex = s_wrong.toString(16);

        // 发送验证请求 - 应该失败
        AuthDTOs.VerifyRequest verifyReq = new AuthDTOs.VerifyRequest(
                username, challengeId, s_wrong_hex, R_hex
        );

        StepVerifier.create(authService.verifyAndLogin(verifyReq))
                .expectError(SecurityException.class)
                .verify();

        log.info("✓ 正确拒绝无效证明");
    }

    /**
     * 测试场景3: 重放攻击防护
     */
    @Test
    @DisplayName("场景3: 重放攻击防护 - 挑战一次性使用")
    void testReplayAttack() {
        String username = "testuser_" + generateRandomHex(8);

        // 注册用户
        BigInteger x = new BigInteger(256, random).mod(group.q());
        BigInteger Y = group.g().modPow(x, group.p());
        String Y_hex = Y.toString(16);

        authService.register(new AuthDTOs.RegisterRequest(username, Y_hex, generateRandomHex(16))).block();

        // 获取挑战
        BigInteger r = new BigInteger(256, random).mod(group.q());
        BigInteger R = group.g().modPow(r, group.p());
        String R_hex = R.toString(16);

        AuthDTOs.ChallengeResponse challengeResp = authService.createChallenge(
                new AuthDTOs.ChallengeRequest(username, R_hex)
        ).block();

        String challengeId = challengeResp.challengeId();
        BigInteger c = new BigInteger(challengeResp.c(), 16);

        // 计算正确的s
        BigInteger s = r.add(c.multiply(x)).mod(group.q());
        String s_hex = s.toString(16);

        AuthDTOs.VerifyRequest verifyReq = new AuthDTOs.VerifyRequest(
                username, challengeId, s_hex, R_hex
        );

        // 第一次验证 - 应该成功
        AuthDTOs.AuthResponse authResp = authService.verifyAndLogin(verifyReq).block();
        assertThat(authResp).isNotNull();
        assertThat(authResp.token()).isNotNull();

        log.info("第一次验证成功");

        // 第二次验证（重放）- 应该失败
        StepVerifier.create(authService.verifyAndLogin(verifyReq))
                .expectError(IllegalStateException.class) // Challenge expired or not found
                .verify();

        log.info("✓ 正确阻止重放攻击");
    }

    /**
     * 测试场景4: 不存在用户（防枚举）
     */
    @Test
    @DisplayName("场景4: 不存在用户 - 防枚举攻击")
    void testNonexistentUser() {
        String username = "nonexistent_" + generateRandomHex(16);

        // 请求挑战 - 应该返回假挑战，不暴露用户不存在
        BigInteger R = group.g().modPow(new BigInteger(256, random).mod(group.q()), group.p());
        String R_hex = R.toString(16);

        AuthDTOs.ChallengeResponse challengeResp = authService.createChallenge(
                new AuthDTOs.ChallengeRequest(username, R_hex)
        ).block();

        // 验证返回了挑战（不暴露用户不存在）
        assertThat(challengeResp).isNotNull();
        assertThat(challengeResp.challengeId()).isNotNull();
        assertThat(challengeResp.c()).isNotNull();

        log.info("✓ 对不存在用户返回假挑战，防止用户枚举");
    }

    /**
     * 测试场景5: 篡改R值
     */
    @Test
    @DisplayName("场景5: 篡改检测 - 修改clientR")
    void testTamperedR() {
        String username = "testuser_" + generateRandomHex(8);

        // 注册用户
        BigInteger x = new BigInteger(256, random).mod(group.q());
        BigInteger Y = group.g().modPow(x, group.p());
        String Y_hex = Y.toString(16);

        authService.register(new AuthDTOs.RegisterRequest(username, Y_hex, generateRandomHex(16))).block();

        // 获取挑战
        BigInteger r = new BigInteger(256, random).mod(group.q());
        BigInteger R = group.g().modPow(r, group.p());
        String R_hex = R.toString(16);

        AuthDTOs.ChallengeResponse challengeResp = authService.createChallenge(
                new AuthDTOs.ChallengeRequest(username, R_hex)
        ).block();

        String challengeId = challengeResp.challengeId();
        BigInteger c = new BigInteger(challengeResp.c(), 16);

        // 计算正确的s
        BigInteger s = r.add(c.multiply(x)).mod(group.q());
        String s_hex = s.toString(16);

        // 篡改R值
        BigInteger R_tampered = R.add(BigInteger.ONE);
        String R_tampered_hex = R_tampered.toString(16);

        AuthDTOs.VerifyRequest verifyReq = new AuthDTOs.VerifyRequest(
                username, challengeId, s_hex, R_tampered_hex
        );

        // 验证应该失败
        StepVerifier.create(authService.verifyAndLogin(verifyReq))
                .expectError(SecurityException.class)
                .verify();

        log.info("✓ 正确检测到R值篡改");
    }
}
