package com.polymarket.clob.funxyz;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打真实 https://api.fun.xyz/v1/eoa。
 * <p>默认跳过；本机运行：{@code FUNXYZ_IT=true mvn test -Dtest=FunxyzClientIntegrationTest}</p>
 */
@EnabledIfEnvironmentVariable(named = "FUNXYZ_IT", matches = "true")
class FunxyzClientIntegrationTest {

    /** HAR 抓到的固定 EOA。 */
    private static final Address EOA =
            Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    /** HAR 抓到的 recipient。 */
    private static final Address RECIPIENT =
            Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");
    /** fun.xyz 应该持久映射到这个 EVM 地址。 */
    private static final Address EXPECTED_EVM =
            Address.fromHex("0x4C741213d8519429002ab3E69DE9620fb9b48C69");

    @Test
    void liveCallReturnsHarAddress() throws Exception {
        FunxyzClient client = new FunxyzClient(FunxyzConfig.builder().build());

        DepositAddresses addrs = client.getDepositAddresses(EOA, RECIPIENT).get();

        assertThat(addrs.evm()).isEqualTo(EXPECTED_EVM);
        assertThat(addrs.solana()).isNotEmpty();
        assertThat(addrs.tron()).isNotEmpty();
        assertThat(addrs.btcSegwit()).isNotEmpty();
    }
}
