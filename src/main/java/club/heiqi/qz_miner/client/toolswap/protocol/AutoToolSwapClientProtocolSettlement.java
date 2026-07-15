package club.heiqi.qz_miner.client.toolswap.protocol;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;

/** 客户端精确归因后接受的单次动作结算。 */
public final class AutoToolSwapClientProtocolSettlement {

    private final AutoToolSwapIntent intent;
    private final AutoToolSwapRoundResult result;

    AutoToolSwapClientProtocolSettlement(AutoToolSwapIntent intent, AutoToolSwapRoundResult result) {
        this.intent = intent;
        this.result = result;
    }

    /** @return 已发送且被精确匹配的不可变请求。 */
    public AutoToolSwapIntent intent() {
        return intent;
    }

    /** @return 服务端回传的不可变结果。 */
    public AutoToolSwapRoundResult result() {
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AutoToolSwapClientProtocolSettlement
                && intent.equals(((AutoToolSwapClientProtocolSettlement) other).intent)
                && result.equals(((AutoToolSwapClientProtocolSettlement) other).result));
    }

    @Override
    public int hashCode() {
        return 31 * intent.hashCode() + result.hashCode();
    }
}
