package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;

/**
 * 阶段5 起为单次执行承载目标队列与代际；阶段8 块2 起追加 session 字段，
 * 供真实破坏桥（{@link ChainExecutionEventBridge}）在主线程消费时解析 mode/subMode →
 * ChainModeRegistry → ChainActionExecutor。
 *
 * <p>承载一次连锁会话的目标队列 + 代际 + session，由 {@link ChainPlanningEventBridge} worker
 * 完成时构造并存入 {@link ChainExecutionContextRegistry}，由
 * {@link ChainExecutionEventBridge} 在主线程消费。</p>
 *
 * <h3>与 ChainSession 的关系（E1 阶段8 接线）</h3>
 * <p>阶段8 块2 起，本类持有 worker 创建的 shadowSession 引用，作为真实破坏桥的 session 参数载体。
 * session 本身只承载配置性字段（mode/subMode/origin/interactFace/hit 偏移/radius/maxBlocks），
 * 真实破坏桥<b>不</b>读 session 的 pendingBreakTargets/plannerRunning 等运行态字段
 * （这些字段块3 字段瘦身时一起清）。session 是只读配置载体，不破坏 I1。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本类只承载数据（队列 + 代际 + 节流戳 + session），<b>不</b>触碰世界、
 *       <b>不</b>切执行态、<b>不</b>调任何破坏方块 API。真实破坏发生在主线程消费订阅者
 *       （{@link ChainExecutionEventBridge#onServerTick} ServerTickEvent.START）经
 *       {@link club.heiqi.qz_miner.chain.executor.ChainActionExecutor#execute} 调起，
 *       本类仅提供数据。</li>
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
     * 阶段8 块2：worker 装配的 shadowSession 引用。真实破坏桥据此解析 mode/subMode →
     * ChainModeDefinition.resolveActionExecutor → ChainActionExecutor。session 仅承载
     * 配置性字段，是只读载体。
     */
    private final ChainSession session;
    /**
     * 独立节流字段：下次允许执行器消费的毫秒戳。
     *
     * <p>真实破坏桥在 {@code executed>0} 时 set 本字段为 {@code now+50}（对齐旧 ChainExecutor 控速）。</p>
     */
    private volatile long nextExecutorAllowedMillis;

    /**
     * 构造执行上下文。
     *
     * @param playerUUID 触发玩家
     * @param generation 代际（必须与触发本次执行的 {@link club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted}
     *                   的 generation 一致）
     * @param targets    目标队列引用（与 bridge worker 的 shadowQueue 同一引用）
     * @param session    worker 装配的 shadowSession（阶段8 块2 真实破坏桥参数载体，可为 null 供单测用）
     */
    public ChainExecutionContext(UUID playerUUID, int generation, ConcurrentLinkedQueue<ChainTarget> targets, ChainSession session) {
        this.playerUUID = playerUUID;
        this.generation = generation;
        this.targets = targets;
        this.session = session;
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
     * @return worker 装配的 shadowSession（阶段8 块2 真实破坏桥参数载体；可能为 null，单测可不传）
     */
    public ChainSession getSession() {
        return session;
    }

    /**
     * @return 下次允许执行器消费的毫秒戳
     */
    public long getNextExecutorAllowedMillis() {
        return nextExecutorAllowedMillis;
    }

    /**
     * 设置下次允许消费的毫秒戳（真实破坏桥 executed>0 时设 now+50 控速）。
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

    /**
     * 控速闸门：当前时间戳是否已到下次允许执行戳。
     *
     * <p>真实破坏桥在 while 循环前调用本方法判定；未到则 return 留下一 tick 再判。
     * 控速语义对齐旧 {@code ChainExecutor:89-110}。</p>
     *
     * @param nowMillis 当前毫秒戳（System.currentTimeMillis()）
     * @return true 表示已到允许时刻，可进入破坏循环
     */
    public boolean isExecutorReady(long nowMillis) {
        return nowMillis >= nextExecutorAllowedMillis;
    }
}
