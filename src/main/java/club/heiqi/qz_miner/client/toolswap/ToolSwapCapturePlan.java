package club.heiqi.qz_miner.client.toolswap;

/** 单 tick 库存采样强度。 */
public enum ToolSwapCapturePlan {
    NONE,
    PROTECTED,
    FULL,
    /** 使用服务端请求携带的 block id/meta 做完整候选采样。 */
    FULL_TARGET
}
