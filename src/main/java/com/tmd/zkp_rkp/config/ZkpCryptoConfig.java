package com.tmd.zkp_rkp.config;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/27
 */
@Slf4j
@Configuration
public class ZkpCryptoConfig {
    // 使用 Bouncy Castle 作为安全提供者
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // Schnorr群参数 - RFC 3526 1536-bit MODP Group
    // P is a safe prime where P = 2Q + 1
    public static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088" +
                    "A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302" +
                    "B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED" +
                    "6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651E" +
                    "CE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F8365" +
                    "5D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC980" +
                    "4F1746C08CA237327FFFFFFFFFFFFFFFF", 16);
    // Q = (P-1)/2 for safe prime P
    public static final BigInteger Q = P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2));
    public static final BigInteger G = BigInteger.valueOf(2);

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    // 群参数Bean,方便注入
    @Bean
    public SchnorrGroup schnorrGroup() {
        return new SchnorrGroup(P, Q, G);
    }

    public record SchnorrGroup(BigInteger p, BigInteger q, BigInteger g) {
        //验证元素是否在合法范围内
        public boolean isValidElement(BigInteger x) {
            return x != null && x.compareTo(BigInteger.ONE) > 0 && x.compareTo(p) < 0;
        }
    }
}
