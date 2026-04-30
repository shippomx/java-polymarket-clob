package com.polymarket.clob.example;

import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.ChainId;

/**
 * Plan 1 示例：只读查询。
 *
 * <p>运行：
 * <pre>{@code
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.UnauthenticatedExample \
 *     -Dexec.args="<token_id>"
 * }</pre></p>
 */
public final class UnauthenticatedExample {

    private UnauthenticatedExample() {}

    public static void main(String[] args) {
        try (ClobClient client = ClobClient.builder()
                .useDefaultEndpoint()
                .chainId(ChainId.POLYGON)
                .build()) {

            System.out.println("ok         = " + client.market().ok().join());
            System.out.println("serverTime = " + client.market().serverTime().join());

            if (args.length > 0) {
                String tokenId = args[0];
                System.out.println("midpoint(" + tokenId + ")     = "
                        + client.market().getMidpoint(tokenId).join().getMid());
                System.out.println("price(" + tokenId + ", BUY)  = "
                        + client.market().getPrice(tokenId, Side.BUY).join().getPrice());
                System.out.println("price(" + tokenId + ", SELL) = "
                        + client.market().getPrice(tokenId, Side.SELL).join().getPrice());
            } else {
                System.out.println("(提示：传 token_id 作为第一个参数可查询 midpoint / price)");
            }
        }
    }
}
