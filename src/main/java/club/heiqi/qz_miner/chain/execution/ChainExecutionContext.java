package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 承载一次连锁的身份、目标队列与会话配置，供执行桥在服务端主线程消费。
 *
 * <p>规划桥登记上下文并由 worker 向并发队列追加目标；registry 发布引用，volatile 完成标志
 * 发布最终规划结果。执行桥在规划完成且队列耗尽后结算执行。
 * generation 与 serverRoundId 随事件注入并保持不可变，完成事件沿用这组身份。</p>
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
    /** 会话配置载体；执行桥据 mode/subMode 解析执行器。 */
    private final ChainSession session;
    /** round 级有界规划诊断器；仅持有纯值计数与文本快照。 */
    private final club.heiqi.qz_miner.chain.planner.ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;
    /** worker 完成遍历并发布 PlanCompleted 后置位；volatile 向主线程发布此前追加的全部目标。 */
    private volatile boolean planningComplete;
    /** worker 完成时冻结的确认目标数。 */
    private volatile int planningConfirmedCount;
    /** worker 是否确认仍存在第一个无法纳入安全上限的目标。 */
    private volatile boolean planningTargetLimitExceeded;
    /** 主线程已从执行队列消费的目标数。 */
    private int executionConsumedCount;
    /** 主线程实际成功执行的额外目标数。 */
    private int executionSucceededCount;
    /** 主线程因目标局部安全拒绝而跳过的目标数；达到 int 上界后饱和。 */
    private int executionSkippedCount;
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
     * @param session    会话配置载体；验证可传 null
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
        this.planningComplete = false;
        this.planningConfirmedCount = 0;
        this.planningTargetLimitExceeded = false;
    }

    /** 标记 worker 影子遍历完成（worker 完成路径调用，主线程消费订阅者据此判定可否 publish ExecutionFinished）。 */
    public void markPlanningComplete() {
        markPlanningComplete(planningConfirmedCount);
    }

    /** 冻结 worker 确认数并标记规划完成。 */
    public void markPlanningComplete(int confirmedCount) {
        tryCompletePlanningAndPublish(confirmedCount, false, new Runnable() {
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
     * 仅规划仍为 ACTIVE 时执行一次 publication。
     *
     * <p>事件入队是先行线性化点：publication 尚未返回时，规划仍保持 ACTIVE，
     * {@code planningComplete} 与 {@code isCompleted()} 均保持等待；publication 抛出
     * {@link RuntimeException} 或 {@link LinkageError} 时同样保持 ACTIVE，由规划桥负责发布
     * 固定原因的 PlanCancelled。</p>
     */
    public boolean tryCompletePlanningAndPublish(int confirmedCount, Runnable publication) {
        return tryCompletePlanningAndPublish(confirmedCount, false, publication);
    }

    /** 同一完成线性化点冻结 GT 等特殊规划的目标上限截断事实。 */
    public synchronized boolean tryCompletePlanningAndPublish(int confirmedCount,
            boolean targetLimitExceeded, Runnable publication) {
        if (publication == null) throw new IllegalArgumentException("completion publication must not be null");
        if (planningTerminal != PlanningTerminal.ACTIVE) return false;
        publication.run();
        planningConfirmedCount = Math.max(0, confirmedCount);
        planningTargetLimitExceeded = targetLimitExceeded;
        planningTerminal = PlanningTerminal.COMPLETED;
        // 最后写 volatile 标志，向主线程发布 PlanCompleted 已入队及全部规划结果。
        planningComplete = true;
        return true;
    }

    /** worker 在规划仍活跃时发布本次进度，取消后由终局门禁拒绝。 */
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

    /** @return worker 是否已完成遍历并发布规划完成事件 */
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
     * @return 会话配置载体；验证构造可为 null
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

    /** @return 完成的规划是否确认目标集合被安全上限截断 */
    public boolean isPlanningTargetLimitExceeded() {
        return planningTargetLimitExceeded;
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

    /** 记录一个目标局部跳过；计数在 {@link Integer#MAX_VALUE} 饱和。 */
    public void recordExecutionSkipped() {
        if (executionSkippedCount < Integer.MAX_VALUE) executionSkippedCount++;
    }

    /** @return 主线程已消费目标数 */
    public int getExecutionConsumedCount() {
        return executionConsumedCount;
    }

    /** @return 主线程成功执行目标数 */
    public int getExecutionSucceededCount() {
        return executionSucceededCount;
    }

    /** @return 主线程因目标局部拒绝跳过的目标数 */
    public int getExecutionSkippedCount() {
        return executionSkippedCount;
    }

    /** @return 规划已完成且目标队列已耗尽，可发布 ExecutionFinished */
    public boolean isCompleted() {
        return planningComplete && targets.isEmpty();
    }

}
