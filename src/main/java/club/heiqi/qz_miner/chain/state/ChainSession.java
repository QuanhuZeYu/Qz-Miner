package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁任务会话。
 */
public class ChainSession {

    private final ChainRequest request;
    private final ChainRuntimeState runtimeState;

    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin) {
        this(playerUUID, mode, subMode, origin, 1, 0.0F, 0.0F, 0.0F, -1, -1);
    }

    /**
     * 创建连锁会话。
     *
     * @param playerUUID 玩家 UUID
     * @param mode 主模式
     * @param subMode 子模式
     * @param origin 起点坐标
     * @param interactFace 交互点击面
     */
    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace) {
        this(playerUUID, mode, subMode, origin, interactFace, 0.0F, 0.0F, 0.0F, -1, -1);
    }

    /**
     * 创建连锁会话。
     *
     * @param playerUUID 玩家 UUID
     * @param mode 主模式
     * @param subMode 子模式
     * @param origin 起点坐标
     * @param interactFace 交互点击面
     * @param interactHitX 命中点 X 偏移
     * @param interactHitY 命中点 Y 偏移
     * @param interactHitZ 命中点 Z 偏移
     */
    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ) {
        this(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ, -1, -1);
    }

    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ, int requestedChainRadius, int requestedChainMaxBlocks) {
        this(new ChainRequest(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ, requestedChainRadius, requestedChainMaxBlocks));
    }

    public ChainSession(ChainRequest request) {
        this.request = request;
        this.runtimeState = new ChainRuntimeState(request == null ? null : request.getPlayerUUID());
    }

    public UUID getPlayerUUID() {
        return request == null ? null : request.getPlayerUUID();
    }

    public ChainMode getMode() {
        return request == null ? null : request.getMode();
    }

    public ChainSubMode getSubMode() {
        return request == null ? null : request.getSubMode();
    }

    public ChainTarget getOrigin() {
        return request == null ? null : request.getOrigin();
    }

    /**
     * 获取触发交互时记录的点击面。
     *
     * @return 点击面编号
     */
    public int getInteractFace() {
        return request == null ? 1 : request.getInteractFace();
    }

    /**
     * 获取交互命中点 X 偏移。
     *
     * @return 命中点 X 偏移
     */
    public float getInteractHitX() {
        return request == null ? 0.0F : request.getInteractHitX();
    }

    /**
     * 获取交互命中点 Y 偏移。
     *
     * @return 命中点 Y 偏移
     */
    public float getInteractHitY() {
        return request == null ? 0.0F : request.getInteractHitY();
    }

    /**
     * 获取交互命中点 Z 偏移。
     *
     * @return 命中点 Z 偏移
     */
    public float getInteractHitZ() {
        return request == null ? 0.0F : request.getInteractHitZ();
    }

    public ChainRequest getRequest() {
        return request;
    }

    /**
     * 标记会话进入规划阶段，并重置执行节流。
     */
    public void beginPlanning() {
        runtimeState.setPlannerRunning(true);
        runtimeState.setPlannerCompleted(false);
        runtimeState.resetExecutorThrottle();
    }

    /**
     * 标记会话规划结束，并释放规划订阅句柄。
     */
    public void markPlanningCompleted() {
        runtimeState.setPlannerSubscription(null);
        runtimeState.setPlannerRunning(false);
        runtimeState.setPlannerCompleted(true);
    }

    /**
     * 获取规划遍历使用的候选队列。
     */
    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return runtimeState.getTraversalTargets();
    }

    /**
     * 获取待执行破坏目标队列。
     */
    public ConcurrentLinkedQueue<ChainTarget> getPendingBreakTargets() {
        return runtimeState.getPendingBreakTargets();
    }

    /**
     * 获取当前已匹配目标数。
     */
    public int getMatchedTargetCount() {
        return runtimeState.getMatchedTargetCount();
    }

    /**
     * 更新当前已匹配目标数。
     */
    public void setMatchedTargetCount(int matchedTargetCount) {
        runtimeState.setMatchedTargetCount(matchedTargetCount);
    }

    /**
     * 记录当前规划订阅句柄。
     */
    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        runtimeState.setPlannerSubscription(plannerSubscription);
    }

    /**
     * 判断当前是否仍持有规划订阅句柄。
     */
    public boolean hasPlannerSubscription() {
        return runtimeState.getPlannerSubscription() != null;
    }

    /**
     * 判断当前规划是否已经完成。
     */
    public boolean isPlannerCompleted() {
        return runtimeState.isPlannerCompleted();
    }

    /**
     * 判断当前规划是否仍在运行。
     */
    public boolean isPlannerRunning() {
        return runtimeState.isPlannerRunning();
    }

    /**
     * 判断执行器当前是否允许继续消费目标。
     */
    public boolean isExecutorReady(long nowMillis) {
        return runtimeState.isExecutorReady(nowMillis);
    }

    /**
     * 为下一次执行消费设置节流时间。
     */
    public void scheduleNextExecutorRun(long nowMillis, long intervalMillis) {
        runtimeState.scheduleNextExecutorRun(nowMillis, intervalMillis);
    }

    public void clearRuntimeState(String reason) {
        runtimeState.clear(reason);
    }

    /**
     * 停止执行但保留玩家级掉落缓冲。
     */
    public void stopExecutionPreservingDrops(String reason) {
        runtimeState.stopExecutionPreservingDrops(reason);
    }
}
