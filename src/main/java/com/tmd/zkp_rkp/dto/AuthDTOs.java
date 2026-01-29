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

    //请求DTOs

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 32, message = "用户名长度3-32字符")
            @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母数字下划线")
            String username,

            @NotBlank(message = "公钥不能为空")
            @Pattern(regexp = "^[0-9A-Fa-f]+$", message = "公钥必须是十六进制字符串")
            String publicKeyY,  // 十六进制公钥 Y = g^x mod p

            @NotBlank(message = "盐值不能为空")
            String salt
    ) {
    }

    public record ChallengeRequest(
            @NotBlank(message = "用户名不能为空")
            String username,

            @NotBlank(message = "承诺值R不能为空")
            @Pattern(regexp = "^[0-9A-Fa-f]+$", message = "R必须是十六进制")
            String clientR // 客户端生成的承诺值 R = g^r mod p
    ) {
    }

    public record VerifyRequest(
            @NotBlank(message = "挑战ID不能为空")
            String challengeId,

            @NotBlank(message = "证明值s不能为空")
            @Pattern(regexp = "^[0-9A-Fa-f]+$", message = "s必须是十六进制")
            String s,      // 证明响应值 s = r + c*x mod q

            @NotBlank(message = "承诺值R不能为空")
            @Pattern(regexp = "^[0-9A-Fa-f]+$", message = "R必须是十六进制")
            String clientR, // 承诺值 R = g^r mod p

            @NotBlank(message = "用户名不能为空")
            String username
    ) {
        public com.tmd.zkp_rkp.service.crypto.ZkpService.SchnorrProof toProof() {
            return new com.tmd.zkp_rkp.service.crypto.ZkpService.SchnorrProof(
                    new BigInteger(s, 16),
                    new BigInteger(clientR, 16),
                    username
            );
        }
    }

    //响应 DTOs

    public record ChallengeResponse(
            String challengeId,
            String c,          // 挑战值 c = H(R || Y || username) 十六进制
            String p,          // 十六进制
            String q,          // 十六进制
            String g           // 十六进制
    ) {
        public static ChallengeResponse fromServiceRecord(
                com.tmd.zkp_rkp.service.crypto.ZkpService.Challenge ch) {
            return new ChallengeResponse(
                    ch.challengeId(),
                    ch.c().toString(16),
                    ch.p().toString(16),
                    ch.q().toString(16),
                    ch.g().toString(16)
            );
        }
    }

    public record AuthResponse(
            String token,
            String type,
            String username,
            long expiresIn
    ) {
    }

    public record ErrorResponse(
            String code,
            String message,
            long timestamp
    ) {
    }
}
