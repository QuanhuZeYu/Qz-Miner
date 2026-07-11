package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁任务会话。
 *
 * <p>阶段8 块3 瘦身：删除旧链路委托方法（beginPlanning/markPlanningCompleted/getPendingBreakTargets/
 * getMatchedTargetCount/setMatchedTargetCount/isPlannerCompleted/isPlannerRunning/isExecutorReady/
 * scheduleNextExecutorRun/stopExecutionPreservingDrops）。新链路目标队列/节流/matchedCount 由
 * {@link club.heiqi.qz_miner.chain.execution.ChainExecutionContext} 承载，session 仅作配置载体
 * （mode/subMode/origin/interactFace/hitOffset/radius/maxBlocks）+ 装配 traverser 的 traversalTargets。
 * 真实破坏桥（{@link club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge}）只读 session 配置字段，
 * 不读运行态字段（块3 已删）。</p>
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

    /** 创建带冻结对象组快照的单次会话。 */
    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin,
            int interactFace, float interactHitX, float interactHitY, float interactHitZ,
            int requestedChainRadius, int requestedChainMaxBlocks, ObjectGroup selectedObjectGroup) {
        this(new ChainRequest(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY,
                interactHitZ, requestedChainRadius, requestedChainMaxBlocks, selectedObjectGroup));
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
     * 获取规划遍历使用的候选队列（ChainPlanningRuntimeFactory 装配 traverser 用）。
     */
    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return runtimeState.getTraversalTargets();
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

    public void clearRuntimeState(String reason) {
        runtimeState.clear(reason);
    }
}
