package com.tmd.zkp_rkp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigInteger;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
public class AuthDTOs {

    //注册请求
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32)String username,
            @NotBlank @Pattern(regexp = "^[0-9A-Fa-f]+$") String publicKeyY, //十六机制的公钥hh
            @NotBlank String salt
    ){}

    // 登录挑战请求
    public record ChallengeRequest(
            @NotBlank String username
    ) {}

    // 登录挑战响应
    public record ChallengeResponse(
            String challengeId,
            String R,          // 十六进制
            String p,          // 十六进制
            String q,          // 十六进制
            String g           // 十六进制
    ) {
        public static ChallengeResponse fromServiceRecord(
                com.tmd.zkp_rkp.service.crypto.ZkpService.Challenge ch) {
            return new ChallengeResponse(
                    ch.challengeId(),
                    ch.R().toString(16),
                    ch.p().toString(16),
                    ch.q().toString(16),
                    ch.g().toString(16)
            );
        }
    }

    // 验证请求（ZKP 证明）
    public record VerifyRequest(
            @NotBlank String challengeId,
            @NotBlank @Pattern(regexp = "^[0-9A-Fa-f]+$") String s,      // 证明响应值
            @NotBlank @Pattern(regexp = "^[0-9A-Fa-f]+$") String clientR // 承诺值
    ) {
        public com.tmd.zkp_rkp.service.crypto.ZkpService.SchnorrProof toProof() {
            return new com.tmd.zkp_rkp.service.crypto.ZkpService.SchnorrProof(
                    new BigInteger(s, 16),
                    new BigInteger(clientR, 16)
            );
        }
    }

    // 登录成功响应
    public record AuthResponse(
            String token,
            String type,
            String username,
            long expiresIn
    ) {}

    // 错误响应
    public record ErrorResponse(
            String code,
            String message,
            long timestamp
    ) {}
}
