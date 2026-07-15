package club.heiqi.qz_miner.client.toolswap.protocol;

/** 客户端自动工具换位协议核心的本地生命周期阶段。 */
public enum AutoToolSwapClientProtocolPhase {
    /** 尚未创建 round。 */
    IDLE,
    /** 已发起 round，等待服务端激活回执。 */
    WAIT_ROUND,
    /** 当前服务端 round 可发送常规动作。 */
    OPEN,
    /** 正在收尾，只允许 RESTORE 或 CLOSE。 */
    CLOSING,
    /** 服务端已结束当前 round。 */
    FINISHED,
    /** 协议归因或同步已经失效，交由运行态收口。 */
    ORPHANED
}
