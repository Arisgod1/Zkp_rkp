package com.tmd.zkp_rkp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Entity
@Table(name = "user_credentials", indexes = {
        @Index(name = "idx_username", columnList = "username", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCredentials {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    // 存储公钥 Y = g^x mod p (使用TEXT因为 BigInteger 可能很大)
    @Column(name = "public_key_y", nullable = false, columnDefinition = "TEXT")
    private String publicKeyY;  // 以十六进制字符串存储

    @Column(nullable = false)
    private String salt;  // 额外的盐值，用于增强哈希

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // 辅助方法
    public BigInteger getPublicKeyYAsBigInteger() {
        return new BigInteger(publicKeyY, 16);
    }

    public void setPublicKeyYFromBigInteger(BigInteger y) {
        this.publicKeyY = y.toString(16);
    }
}
