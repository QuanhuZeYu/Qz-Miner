package club.heiqi.qz_miner.client.toolswap;

/** 自动工具换位顶层六态。 */
public enum AutoToolSwapState {
    IDLE,
    PREPARING,
    FROZEN,
    RESTORING,
    WAIT_RELEASE,
    ABORTED_SYNC
}
