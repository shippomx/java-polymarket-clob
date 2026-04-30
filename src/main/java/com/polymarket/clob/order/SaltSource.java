package com.polymarket.clob.order;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * 订单 {@code salt} 生成策略。
 *
 * <p>默认 {@link #secureRandom()} 基于 {@link SecureRandom} 产生正 64 位整数，满足 EIP-712
 * {@code uint256} 需求；Polymarket 上游仅要求 salt 非零 + 差异化即可，256 位熵并不必需。</p>
 *
 * <p>测试可注入固定 salt（常量 lambda）以产出可复现的 golden 向量。</p>
 */
@FunctionalInterface
public interface SaltSource {

    /** 生成一个非负 salt；不得返回 {@code null}。 */
    BigInteger next();

    /** 共享的 {@link SecureRandom} 包装，线程安全。 */
    static SaltSource secureRandom() {
        return Holder.SECURE;
    }

    /** 固定 salt 的工厂，便于测试。 */
    static SaltSource fixed(BigInteger salt) {
        if (salt == null || salt.signum() < 0) {
            throw new IllegalArgumentException("fixed salt must be non-negative: " + salt);
        }
        return () -> salt;
    }

    final class Holder {
        private static final SecureRandom RNG = new SecureRandom();
        private static final SaltSource SECURE = () -> {
            long n = RNG.nextLong() & Long.MAX_VALUE;
            return BigInteger.valueOf(n);
        };

        private Holder() {}
    }
}
