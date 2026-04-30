package com.polymarket.clob.model;

import lombok.Value;
import lombok.experimental.Accessors;

import java.util.Optional;

/**
 * 钱包派生所需的工厂合约地址，对应 Rust {@code rs-clob-client/src/lib.rs} 中的
 * {@code WalletContractConfig}。Amoy 测试网不提供 Proxy 工厂。
 */
@Value(staticConstructor = "of")
@Accessors(fluent = true)
public class WalletContractConfig {
    Optional<Address> proxyFactory;
    Address safeFactory;
}
