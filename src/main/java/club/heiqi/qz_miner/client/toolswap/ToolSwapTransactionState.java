package club.heiqi.qz_miner.client.toolswap;

/** 服务端 round 与库存可见性双门的纯核心子状态。 */
public enum ToolSwapTransactionState {
    IDLE,
    ROUND_PENDING,
    ACTION_RESULT_PENDING,
    INVENTORY_SYNC_VERIFY,
    PROTOCOL_ORPHANED
}
