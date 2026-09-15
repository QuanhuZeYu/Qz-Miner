package club.heiqi.qz_miner.chain.statemachine;

/**
 * 连锁框架状态机的五态枚举。
 *
 * <p>本枚举落地唯一写权威「合法转移表」的状态空间：
 * 状态变更经唯一的 {@link ChainStateMachine} 合法转移表驱动，外部只能 {@code bus.publish} 事件，
 * 不能直接调用 {@code transition} 切态。</p>
 *
 * <ul>
 *   <li>{@link #IDLE}：空闲态，无活跃连锁，等待玩家按下连锁键武装</li>
 *   <li>{@link #ARMED}：待命已武装等待破坏点火，根治触发竞态——按下键到真正破坏方块之间的窗口期内
 *       不丢失玩家意图，也不把"按键"和"破坏"耦合在同一帧。语义是<b>按住期间持续授权</b>：一次连锁
 *       收尾回到 {@link #IDLE} 后，只要按键电平仍为按下就立即回到本态（T11），按住不放即可连续连锁</li>
 *   <li>{@link #PLANNING}：规划进行中，并行 worker 正在算要挖哪些方块（守边界不越权：worker 只读世界只产规划）</li>
 *   <li>{@link #RUNNING}：执行进行中，主线程节流消费规划候选、真实破坏方块（守边界不越权：主线程独占世界写入）</li>
 *   <li>{@link #FINISHING}：显式收尾态，守协作式取消 → 显式收尾——让 worker 停到安全边界、让掉落聚合完成</li>
 * </ul>
 */
public enum ChainPhase {
    /** 空闲态：无活跃连锁，等待玩家武装。 */
    IDLE,
    /** 待命已武装等待破坏点火，根治触发竞态。 */
    ARMED,
    /** 规划进行中，并行 worker 只读世界只产规划。 */
    PLANNING,
    /** 执行进行中，主线程独占真实破坏方块。 */
    RUNNING,
    /** 显式收尾态，守协作式取消 → 显式收尾。 */
    FINISHING
}