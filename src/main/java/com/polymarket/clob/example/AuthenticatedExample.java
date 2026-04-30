package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.AssetType;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.ChainId;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Plan 2 示例：完成 L1/L2 认证后查询账户数据。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code CLOB_ENDPOINT}（可选，默认 {@code https://clob.polymarket.com}）</li>
 *   <li>{@code CLOB_CHAIN_ID}（可选，{@code POLYGON}|{@code AMOY}，默认 {@code POLYGON}）</li>
 *   <li>{@code CLOB_PRIVATE_KEY}（<b>必填</b>，0x 前缀的 EOA 私钥）</li>
 *   <li>{@code CLOB_SIGNATURE_TYPE}（可选，{@code EOA}|{@code POLY_PROXY}|{@code POLY_GNOSIS_SAFE}，默认 {@code POLY_PROXY}）</li>
 * </ul>
 *
 * <p>运行：
 * <pre>{@code
 * export CLOB_PRIVATE_KEY=0xac0974...
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.AuthenticatedExample
 * }</pre>
 *
 * <p>示例最后调用 {@link AuthenticatedClobClient#balanceAllowance} 查询 USDC（COLLATERAL）
 * 余额；如传入 token id 作为第一个 CLI 参数，则额外查询该 ERC-1155 头寸的 allowance。</p>
 */
public final class AuthenticatedExample {

    private AuthenticatedExample() {}

    public static void main(String[] args) {
        String privateKey = require("CLOB_PRIVATE_KEY");
        String endpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        SignatureType sigType = parseSignatureType(System.getenv("CLOB_SIGNATURE_TYPE"));

        Signer signer = LocalSigner.fromPrivateKey(privateKey);
        System.out.println("EOA         = " + signer.address().toHex());

        try (ClobClient base = ClobClient.builder()
                .endpoint(endpoint)
                .chainId(chainId)
                .build()) {

            AuthenticatedClobClient client = base
                    .authenticate(signer, sigType, BigInteger.ZERO)
                    .join();

            System.out.println("funder      = " + client.funder().toHex()
                    + "  (" + client.signatureType() + ")");
            System.out.println("apiKeyId    = " + client.apiKeyId());
            System.out.println("closedOnly  = " + client.closedOnlyMode().join().closedOnly());

            BalanceAllowanceRequest.Builder req = BalanceAllowanceRequest.builder()
                    .assetType(AssetType.COLLATERAL);
            if (args.length > 0) {
                req.assetType(AssetType.CONDITIONAL).tokenId(new BigInteger(args[0]));
            }
            var resp = client.balanceAllowance(req.build()).join();
            System.out.println("balance     = " + resp.balance());
            resp.allowances().forEach((operator, amount) ->
                    System.out.println("  allowance " + operator.toHex() + " = " + amount));
        }
    }

    private static String require(String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("环境变量 " + env + " 未设置");
        }
        return v;
    }

    private static long parseChainId(String raw) {
        if (raw == null || raw.isBlank() || "POLYGON".equalsIgnoreCase(raw)) {
            return ChainId.POLYGON;
        }
        if ("AMOY".equalsIgnoreCase(raw)) {
            return ChainId.AMOY;
        }
        return Long.parseLong(raw);
    }

    private static SignatureType parseSignatureType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SignatureType.POLY_PROXY;
        }
        return SignatureType.valueOf(raw.trim().toUpperCase());
    }
}
