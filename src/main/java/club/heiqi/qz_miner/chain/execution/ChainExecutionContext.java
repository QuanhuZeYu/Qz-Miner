package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 阶段5 新链路单次执行的上下文（dry-run 数据载体）。
 *
 * <p>承载一次连锁会话的目标队列 + 代际，由 {@link ChainPlanningEventBridge} worker
 * 完成时构造并存入 {@link ChainExecutionContextRegistry}，由
 * {@link ChainExecutionEventBridge} 在主线程消费。</p>
 *
 * <h3>与 ChainSession 的关系（E3-a）</h3>
 * <p>阶段5 <b>不迁移</b> {@code ChainRuntimeState}/{@code ChainSession} 任何字段。
 * 本类是新链路独立结构，与旧链路的 {@code pendingBreakTargets}/{@code plannerRunning} 等
 * 完全隔离。阶段8 旧链路下线时随旧字段一起清理。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本类只承载数据（队列 + 代际 + 节流戳），<b>不</b>触碰世界、<b>不</b>切执行态、
 *       <b>不</b>调任何破坏方块 API。dry-run 阶段（阶段5）消费订阅者 poll 出目标仅计数不破坏。</li>
 *   <li><b>I4</b>：跨线程安全——bridge worker 线程构造 + put registry，主线程消费订阅者 get + poll，
 *       队列与 registry 均为 {@code concurrent} 包线程安全实现。</li>
 *   <li><b>I10</b>：本类 <b>不</b>写状态机，generation 字段 final 不可变，由
 *       {@link ChainPlanningEventBridge} 经 {@code PlanStarted.getGeneration()} 注入。</li>
 * </ul>
 *
 * <h3>线程可见性</h3>
 * <p>{@link #targets} 与 {@link #generation} 经 {@link ChainExecutionContextRegistry#put}
 * （{@link java.util.concurrent.ConcurrentHashMap}）写入，经 registry get 读取时由
 * ConcurrentHashMap 的 happens-before 保证内存可见性，无需额外同步。</p>
 */
public final class ChainExecutionContext {

    /** 触发本次连锁的玩家 UUID。 */
    private final UUID playerUUID;
    /** 本次连锁代际（经 PlanStarted→PlanCompleted 注入，事件流回填 ExecutionFinished）。 */
    private final int generation;
    /** 待消费的目标队列（与 bridge worker 的 shadowQueue 同一引用）。 */
    private final ConcurrentLinkedQueue<ChainTarget> targets;
    /**
     * 独立节流字段：下次允许执行器消费的毫秒戳。
     *
     * <p>dry-run 阶段（阶段5）消费订阅者 <b>不强制</b> 读取本字段（dry-run 零破坏无控速必要）；
     * 留阶段8 真实破坏接管时复用，对齐旧 {@code ChainSession.nextExecutorAllowedMillis} 语义。</p>
     */
    private volatile long nextExecutorAllowedMillis;

    /**
     * 构造执行上下文。
     *
     * @param playerUUID 触发玩家
     * @param generation 代际（必须与触发本次执行的 {@link club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted}
     *                   的 generation 一致）
     * @param targets    目标队列引用（与 bridge worker 的 shadowQueue 同一引用）
     */
    public ChainExecutionContext(UUID playerUUID, int generation, ConcurrentLinkedQueue<ChainTarget> targets) {
        this.playerUUID = playerUUID;
        this.generation = generation;
        this.targets = targets;
        this.nextExecutorAllowedMillis = 0L;
    }

    /** @return 触发玩家 UUID */
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    /** @return 本次代际（事件流回填 ExecutionFinished 的唯一来源） */
    public int getGeneration() {
        return generation;
    }

    /** @return 目标队列（与 bridge worker 的 shadowQueue 同一引用，主线程 poll 消费） */
    public ConcurrentLinkedQueue<ChainTarget> getTargets() {
        return targets;
    }

    /**
     * @return 下次允许执行器消费的毫秒戳（dry-run 不强制读取，留阶段8 复用）
     */
    public long getNextExecutorAllowedMillis() {
        return nextExecutorAllowedMillis;
    }

    /**
     * 设置下次允许消费的毫秒戳（阶段8 真实破坏接管时复用）。
     *
     * @param nextExecutorAllowedMillis 毫秒戳
     */
    public void setNextExecutorAllowedMillis(long nextExecutorAllowedMillis) {
        this.nextExecutorAllowedMillis = nextExecutorAllowedMillis;
    }

    /**
     * 队列是否已消费完。
     *
     * <p>供执行订阅者判定是否 publish {@link club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished}。
     * 空规划边界（卡点5）：构造时若 targets 初始即空，登记后立即判定完成并 publish ExecutionFinished。</p>
     *
     * @return true 表示目标队列已空（消费完成或初始空规划）
     */
    public boolean isCompleted() {
        return targets.isEmpty();
    }
}
