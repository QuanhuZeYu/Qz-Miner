package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

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

    /** 主线程请求停止规划时的线性化结果。 */
    public enum PlanningStopResult {
        CANCELLATION_WON,
        CANCELLATION_ALREADY_WON,
        COMPLETION_PENDING_OBSERVATION,
        COMPLETION_OBSERVED
    }

    /** 规划终局只允许在本对象监视器内单调推进。 */
    private enum PlanningTerminal {
        ACTIVE,
        EXTERNAL_CANCELLED,
        WORKER_CANCELLED,
        COMPLETED
    }

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
    /** round 级有界规划诊断器；仅持有纯值计数与文本快照。 */
    private final club.heiqi.qz_miner.chain.planner.ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;
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
    /** worker 完成时冻结的确认目标数。 */
    private volatile int planningConfirmedCount;
    /** 主线程已从执行队列消费的目标数。 */
    private int executionConsumedCount;
    /** 主线程实际成功执行的额外目标数。 */
    private int executionSucceededCount;
    /** registerPre 返回的协作取消句柄；安装与取消由本对象线性化。 */
    private ParallelTickSubscription planningSubscription;
    private PlanningTerminal planningTerminal = PlanningTerminal.ACTIVE;
    private boolean subscriptionCancellationIssued;
    private boolean planningCompletionObserved;
    private boolean executionStopPending;

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
        this(playerUUID, serverRoundId, generation, targets, session, null);
    }

    /** 构造携带 round 级有界诊断器的执行上下文。 */
    public ChainExecutionContext(UUID playerUUID, long serverRoundId, int generation,
                                 ConcurrentLinkedQueue<ChainTarget> targets, ChainSession session,
                                 club.heiqi.qz_miner.chain.planner.ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        this.playerUUID = playerUUID;
        this.serverRoundId = serverRoundId;
        this.generation = generation;
        this.targets = targets;
        this.session = session;
        this.diagnostics = diagnostics;
        this.nextExecutorAllowedMillis = 0L;
        this.planningComplete = false;
        this.planningConfirmedCount = 0;
    }

    /** 标记 worker 影子遍历完成（worker 完成路径调用，主线程消费订阅者据此判定可否 publish ExecutionFinished）。 */
    public void markPlanningComplete() {
        markPlanningComplete(planningConfirmedCount);
    }

    /** 冻结 worker 确认数并标记规划完成。 */
    public void markPlanningComplete(int confirmedCount) {
        tryCompletePlanningAndPublish(confirmedCount, new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    /**
     * 安装规划订阅；若外部取消已先胜出，安装线程立即补发一次协作取消请求。
     *
     * @param subscription registerPre 返回的订阅句柄
     */
    public void attachPlanningSubscription(ParallelTickSubscription subscription) {
        if (subscription == null) throw new IllegalArgumentException("planning subscription must not be null");
        boolean cancelNow = false;
        synchronized (this) {
            if (planningSubscription != null && planningSubscription != subscription) {
                throw new IllegalStateException("planning subscription already attached");
            }
            planningSubscription = subscription;
            if (planningTerminal == PlanningTerminal.EXTERNAL_CANCELLED
                    && !subscriptionCancellationIssued) {
                subscriptionCancellationIssued = true;
                cancelNow = true;
            }
        }
        if (cancelNow) subscription.unregister();
    }

    /**
     * 主线程请求协作停止规划，并与 worker 完成声明线性化。
     *
     * @return 取消胜出、完成待主线程观察或完成已观察
     */
    public PlanningStopResult requestPlanningStop() {
        ParallelTickSubscription cancelNow = null;
        PlanningStopResult result;
        synchronized (this) {
            executionStopPending = true;
            if (planningTerminal == PlanningTerminal.ACTIVE) {
                planningTerminal = PlanningTerminal.EXTERNAL_CANCELLED;
                result = PlanningStopResult.CANCELLATION_WON;
                if (planningSubscription != null && !subscriptionCancellationIssued) {
                    subscriptionCancellationIssued = true;
                    cancelNow = planningSubscription;
                }
            } else if (planningTerminal == PlanningTerminal.EXTERNAL_CANCELLED
                    || planningTerminal == PlanningTerminal.WORKER_CANCELLED) {
                result = PlanningStopResult.CANCELLATION_ALREADY_WON;
            } else {
                result = planningCompletionObserved
                        ? PlanningStopResult.COMPLETION_OBSERVED
                        : PlanningStopResult.COMPLETION_PENDING_OBSERVATION;
            }
        }
        if (cancelNow != null) cancelNow.unregister();
        return result;
    }

    /** @return 外部主线程取消是否已赢得规划终局。 */
    public synchronized boolean isExternalPlanningCancellationRequested() {
        return planningTerminal == PlanningTerminal.EXTERNAL_CANCELLED;
    }

    /**
     * worker 在同一线性化点发布 PlanCompleted，并在 publication 成功返回后固化完成状态。
     * publication 只能执行一次，且外部取消先胜出时不会执行。
     *
     * <p>事件入队是先行线性化点：publication 尚未返回时，规划仍保持 ACTIVE，
     * {@code planningComplete} 与 {@code isCompleted()} 均不可见为完成；publication 抛出
     * {@link RuntimeException} 或 {@link LinkageError} 时同样保持 ACTIVE，由规划桥负责发布
     * 固定原因的 PlanCancelled。</p>
     */
    public synchronized boolean tryCompletePlanningAndPublish(int confirmedCount, Runnable publication) {
        if (publication == null) throw new IllegalArgumentException("completion publication must not be null");
        if (planningTerminal != PlanningTerminal.ACTIVE) return false;
        publication.run();
        planningConfirmedCount = Math.max(0, confirmedCount);
        planningTerminal = PlanningTerminal.COMPLETED;
        // 最后写 volatile 标志，确保主线程不会在 PlanCompleted publication 之前观察到完成。
        planningComplete = true;
        return true;
    }

    /** worker 仅在规划仍活跃时发布一次进度，避免外部取消胜出后出现迟到事件。 */
    public synchronized boolean publishPlanningProgressIfActive(Runnable publication) {
        if (publication == null) throw new IllegalArgumentException("progress publication must not be null");
        if (planningTerminal != PlanningTerminal.ACTIVE) return false;
        publication.run();
        return true;
    }

    /** worker 自然取消路径只允许发布一次 PlanCancelled。 */
    public synchronized boolean cancelPlanningAndPublishIfActive(Runnable publication) {
        if (publication == null) throw new IllegalArgumentException("cancellation publication must not be null");
        if (planningTerminal != PlanningTerminal.ACTIVE) return false;
        planningTerminal = PlanningTerminal.WORKER_CANCELLED;
        publication.run();
        return true;
    }

    /**
     * 主线程观察已发布的 PlanCompleted，并取得此前积压的执行停止请求。
     *
     * @return true 表示完成虽先胜出，但应在 PlanCompleted 合法进态后立即收口
     */
    public synchronized boolean observePlanningCompletionAndShouldStop() {
        if (planningTerminal != PlanningTerminal.COMPLETED) return false;
        planningCompletionObserved = true;
        return executionStopPending;
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

    /** @return round 级规划诊断器，可为 null */
    public club.heiqi.qz_miner.chain.planner.ChainPlanningRuntimeFactory.PlanningDiagnostics getDiagnostics() {
        return diagnostics;
    }

    /** @return worker 确认目标数 */
    public int getPlanningConfirmedCount() {
        return planningConfirmedCount;
    }

    /** 记录主线程从队列消费一个目标。 */
    public void recordExecutionConsumed() {
        executionConsumedCount++;
    }

    /**
     * 记录一次成功额外执行。
     *
     * @return 是否为本 round 首次成功执行
     */
    public boolean recordExecutionSucceeded() {
        executionSucceededCount++;
        return executionSucceededCount == 1;
    }

    /** @return 主线程已消费目标数 */
    public int getExecutionConsumedCount() {
        return executionConsumedCount;
    }

    /** @return 主线程成功执行目标数 */
    public int getExecutionSucceededCount() {
        return executionSucceededCount;
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
