package com.polymarket.clob.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;

/**
 * 单链合约地址集合。最早对齐 Rust {@code rs-clob-client/src/lib.rs} 的 {@code ContractConfig}（V1）。
 *
 * <p>2026-04-28 CTF Exchange v1→v2 切换后扩展为「V1 + V2 + collateral wrapper」三套。字段语义：
 * <ul>
 *   <li>{@link #exchange()}：V1 CTFExchange / NegRiskCTFExchange（按 negRisk 选）；V1 服务端已停，仅
 *       为兼容历史测试与 parity-oracle 保留。</li>
 *   <li>{@link #collateral()}：链上 ERC20 抵押 token（Polygon 上仍是 USDC.e）。V2 wrapper 见
 *       {@link #pUsd()}。</li>
 *   <li>{@link #conditionalTokens()}：CTF ERC1155。</li>
 *   <li>{@link #negRiskAdapter()}：仅 negRisk 配置存在。</li>
 *   <li>{@link #exchangeV2()}：V2 CTFExchange（domain version="2"）；
 *       {@link #negRiskExchangeV2()}：V2 NegRiskCTFExchange。两者由 v2 EIP-712 签名作为
 *       {@code verifyingContract}。</li>
 *   <li>{@link #pUsd()}：V2 时代 Polymarket 部署的 USDC/USDC.e wrapper（"pUSD"），服务端账本以 pUSD
 *       记账；用户在做 V2 链上结算前可能需要把 USDC.e 兑换为 pUSD。</li>
 * </ul>
 * </p>
 */
@Value
@Accessors(fluent = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContractConfig {

    Address exchange;
    Address collateral;
    Address conditionalTokens;
    Optional<Address> negRiskAdapter;
    Optional<Address> exchangeV2;
    Optional<Address> negRiskExchangeV2;
    Optional<Address> pUsd;

    /**
     * V1 兼容工厂：保留旧调用面（4 参），V2 字段全部置空。
     * 仅用于历史测试与未在 V2 迁移面内的链路。
     */
    public static ContractConfig of(Address exchange,
                                    Address collateral,
                                    Address conditionalTokens,
                                    Optional<Address> negRiskAdapter) {
        return new ContractConfig(
                Objects.requireNonNull(exchange, "exchange"),
                Objects.requireNonNull(collateral, "collateral"),
                Objects.requireNonNull(conditionalTokens, "conditionalTokens"),
                Objects.requireNonNull(negRiskAdapter, "negRiskAdapter"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * V2 完整工厂：补 {@code exchangeV2 / negRiskExchangeV2 / pUsd}。
     *
     * <p>所有 V2 字段以 {@link Optional} 持有，但调用此工厂时通常都已知确切地址；如果某条链上
     * 暂无 V2 部署，使用 {@link #of(Address, Address, Address, Optional)} 即可。</p>
     */
    public static ContractConfig ofV2(Address exchange,
                                      Address collateral,
                                      Address conditionalTokens,
                                      Optional<Address> negRiskAdapter,
                                      Address exchangeV2,
                                      Address negRiskExchangeV2,
                                      Address pUsd) {
        return new ContractConfig(
                Objects.requireNonNull(exchange, "exchange"),
                Objects.requireNonNull(collateral, "collateral"),
                Objects.requireNonNull(conditionalTokens, "conditionalTokens"),
                Objects.requireNonNull(negRiskAdapter, "negRiskAdapter"),
                Optional.of(Objects.requireNonNull(exchangeV2, "exchangeV2")),
                Optional.of(Objects.requireNonNull(negRiskExchangeV2, "negRiskExchangeV2")),
                Optional.of(Objects.requireNonNull(pUsd, "pUsd")));
    }
}
