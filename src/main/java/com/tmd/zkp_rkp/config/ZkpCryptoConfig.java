package com.tmd.zkp_rkp.config;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;

import static com.tmd.zkp_rkp.common.ZkpValue.*;

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
