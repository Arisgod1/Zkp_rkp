package com.tmd.zkp_rkp.common;

import java.time.Duration;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/27
 */
public class ZkpValue {
    // 挑战存储前缀
    public static final String CHALLENGE_PREFIX = "zkp:challenge:";
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
}
