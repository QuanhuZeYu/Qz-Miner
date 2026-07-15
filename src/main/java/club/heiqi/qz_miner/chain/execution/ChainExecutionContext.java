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
    /** 本次连锁的不可变服务端轮次关联。 */
    private final long serverRoundId;
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
     * 流式执行标志：worker 是否已完成影子遍历（所有 confirmed target 已 shadowQueue.add）。
     *
     * <p>流式语义（C 修复）：</p>
     * <ul>
     *   <li>{@code false}：worker 仍在搜，主线程消费订阅者可边 poll 边破坏 worker 边 add 的目标，
     *       但<b>不应</b>因 queue 瞬时为空判定 ExecutionFinished 提前 publish（避免卡 RUNNING）。</li>
     *   <li>{@code true}：worker 已完成遍历 + publish PlanCompleted，主线程此后 queue 空 → publish ExecutionFinished。</li>
     * </ul>
     *
     * <p>线程可见性：worker 线程写（{@link #markPlanningComplete}），主线程消费订阅者读
     * （{@link #isPlanningComplete} / {@link #isCompleted}）。volatile 提供 happens-before，
     * 与 {@link ChainExecutionContextRegistry#put}（ConcurrentHashMap）一同保证
     * worker 在 markPlanningComplete 之前的所有 shadowQueue.add 操作对主线程可见。</p>
     */
    private volatile boolean planningComplete;

    /**
     * 构造执行上下文。
     *
     * <p>构造时 {@code planningComplete=false}（worker 尚未完成遍历）。
     * 由 worker 完成路径显式 {@link #markPlanningComplete()} 翻为 true。</p>
     *
     * @param playerUUID 触发玩家
     * @param generation 代际（必须与触发本次执行的 {@link club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted}
     *                   的 generation 一致）
     * @param targets    目标队列引用（与 bridge worker 的 shadowQueue 同一引用）
     * @param session    worker 装配的 shadowSession（阶段8 块2 真实破坏桥参数载体，可为 null 供单测用）
     */
    public ChainExecutionContext(UUID playerUUID, int generation, ConcurrentLinkedQueue<ChainTarget> targets, ChainSession session) {
        this(playerUUID, club.heiqi.qz_miner.chain.eventbus.ChainEvent.NO_SERVER_ROUND_ID,
                generation, targets, session);
    }

    /**
     * 构造带不可变服务端轮次关联的执行上下文。
     *
     * @param playerUUID    触发玩家
     * @param serverRoundId 服务端分配的不可变轮次 ID
     * @param generation    代际
     * @param targets       目标队列
     * @param session       worker 装配的 shadowSession
     */
    public ChainExecutionContext(UUID playerUUID, long serverRoundId, int generation,
                                 ConcurrentLinkedQueue<ChainTarget> targets, ChainSession session) {
        this.playerUUID = playerUUID;
        this.serverRoundId = serverRoundId;
        this.generation = generation;
        this.targets = targets;
        this.session = session;
        this.nextExecutorAllowedMillis = 0L;
        this.planningComplete = false;
    }

    /** 标记 worker 影子遍历完成（worker 完成路径调用，主线程消费订阅者据此判定可否 publish ExecutionFinished）。 */
    public void markPlanningComplete() {
        this.planningComplete = true;
    }

    /** @return worker 是否已完成影子遍历（false 表示仍在搜，主线程消费 queue 空 不应 publish ExecutionFinished） */
    public boolean isPlanningComplete() {
        return planningComplete;
    }

    /** @return 触发玩家 UUID */
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    /** @return 本次代际（事件流回填 ExecutionFinished 的唯一来源） */
    public int getGeneration() {
        return generation;
    }

    /** @return 本次执行的不可变服务端轮次 ID */
    public long getServerRoundId() {
        return serverRoundId;
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
     * 执行是否已完成（流式语义）。
     *
     * <p>流式语义（C 修复）：返回 {@code planningComplete && targets.isEmpty()}，
     * <b>必须</b>同时满足「worker 已完成遍历」和「目标队列已消费空」两个条件：
     * 避免 worker 仍在搜、queue 瞬时为空就被误判 ExecutionFinished 提前 publish
     * （否则卡死 RUNNING，玩家槽哑火）。</p>
     *
     * <ul>
     *   <li>空规划边界（卡点5）：worker 完成时 markPlanningComplete + queue 初始即空 → 立即判定完成。</li>
     *   <li>流式中途：planningComplete=false → 即便 queue 此刻空也返回 false（留 worker 继续搜）。</li>
     *   <li>正常消费完成：planningComplete=true 且 queue poll 空 → 返回 true（触发 publish ExecutionFinished）。</li>
     * </ul>
     *
     * @return true 表示 worker 已完成遍历且目标队列已空（可 publish ExecutionFinished）
     */
    public boolean isCompleted() {
        return planningComplete && targets.isEmpty();
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
