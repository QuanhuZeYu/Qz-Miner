package club.heiqi.qz_miner.client.toolswap;

/** 原版库存事务的纯核心子状态。 */
public enum ToolSwapTransactionState {
    IDLE,
    WAIT_PACKET_ID,
    WAIT_ACK,
    WAIT_SYNC_TICK,
    VERIFY_SLOTS,
    SYNC_ISOLATION
}
