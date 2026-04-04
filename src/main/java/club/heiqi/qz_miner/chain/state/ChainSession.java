package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁任务会话。
 */
public class ChainSession {

    private final UUID playerUUID;
    private final ChainMode mode;
    private final ChainSubMode subMode;
    private final ChainTarget origin;
    private final int interactFace;
    private final float interactHitX;
    private final float interactHitY;
    private final float interactHitZ;
    private volatile ParallelTickSubscription plannerSubscription;
    private volatile ParallelTickSubscription executorSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> traversalTargets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChainTarget> pendingBreakTargets = new ConcurrentLinkedQueue<>();
    private volatile boolean plannerRunning;
    private volatile boolean plannerCompleted;
    private volatile long plannerHeartbeatMillis;
    private volatile int matchedTargetCount;
    private final AtomicLong nextExecutorAllowedMillis = new AtomicLong();

    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin) {
        this(playerUUID, mode, subMode, origin, 1, 0.0F, 0.0F, 0.0F);
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
        this(playerUUID, mode, subMode, origin, interactFace, 0.0F, 0.0F, 0.0F);
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
        this.playerUUID = playerUUID;
        this.mode = mode;
        this.subMode = ChainModeRegistry.resolveSubMode(mode, subMode);
        this.origin = origin;
        this.interactFace = interactFace;
        this.interactHitX = interactHitX;
        this.interactHitY = interactHitY;
        this.interactHitZ = interactHitZ;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public ChainMode getMode() {
        return mode;
    }

    public ChainSubMode getSubMode() {
        return subMode;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    /**
     * 获取触发交互时记录的点击面。
     *
     * @return 点击面编号
     */
    public int getInteractFace() {
        return interactFace;
    }

    /**
     * 获取交互命中点 X 偏移。
     *
     * @return 命中点 X 偏移
     */
    public float getInteractHitX() {
        return interactHitX;
    }

    /**
     * 获取交互命中点 Y 偏移。
     *
     * @return 命中点 Y 偏移
     */
    public float getInteractHitY() {
        return interactHitY;
    }

    /**
     * 获取交互命中点 Z 偏移。
     *
     * @return 命中点 Z 偏移
     */
    public float getInteractHitZ() {
        return interactHitZ;
    }

    public ParallelTickSubscription getPlannerSubscription() {
        return plannerSubscription;
    }

    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        if (this.plannerSubscription == null && plannerSubscription != null) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerSubscription attached", playerUUID);
        } else if (this.plannerSubscription != null && plannerSubscription == null) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerSubscription cleared queuedTargets={}",
                playerUUID, pendingBreakTargets.size());
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ParallelTickSubscription getExecutorSubscription() {
        return executorSubscription;
    }

    public void setExecutorSubscription(ParallelTickSubscription executorSubscription) {
        if (this.executorSubscription == null && executorSubscription != null) {
            MyMod.LOG.debug("[ChainSession] Player {} executorSubscription attached", playerUUID);
        } else if (this.executorSubscription != null && executorSubscription == null) {
            MyMod.LOG.debug("[ChainSession] Player {} executorSubscription cleared queuedTargets={}",
                playerUUID, pendingBreakTargets.size());
        }
        this.executorSubscription = executorSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return traversalTargets;
    }

    public ConcurrentLinkedQueue<ChainTarget> getPendingBreakTargets() {
        return pendingBreakTargets;
    }

    public boolean isPlannerRunning() {
        return plannerRunning;
    }

    public void setPlannerRunning(boolean plannerRunning) {
        if (this.plannerRunning != plannerRunning) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerRunning {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerRunning, plannerRunning, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerRunning = plannerRunning;
    }

    public boolean isPlannerCompleted() {
        return plannerCompleted;
    }

    public void setPlannerCompleted(boolean plannerCompleted) {
        if (this.plannerCompleted != plannerCompleted) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerCompleted {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerCompleted, plannerCompleted, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerCompleted = plannerCompleted;
    }

    public long getPlannerHeartbeatMillis() {
        return plannerHeartbeatMillis;
    }

    public void updatePlannerHeartbeat() {
        this.plannerHeartbeatMillis = System.currentTimeMillis();
    }

    public int getMatchedTargetCount() {
        return matchedTargetCount;
    }

    public void setMatchedTargetCount(int matchedTargetCount) {
        this.matchedTargetCount = Math.max(0, matchedTargetCount);
    }

    public void resetExecutorThrottle() {
        nextExecutorAllowedMillis.set(0L);
    }

    public boolean isExecutorReady(long nowMillis) {
        return nowMillis >= nextExecutorAllowedMillis.get();
    }

    public void scheduleNextExecutorRun(long nowMillis, long intervalMillis) {
        nextExecutorAllowedMillis.set(nowMillis + Math.max(0L, intervalMillis));
    }

    public void clearRuntimeState(String reason) {
        int queuedTargets = pendingBreakTargets.size();
        if (plannerSubscription != null) {
            plannerSubscription.unregister();
            setPlannerSubscription(null);
        }
        if (executorSubscription != null) {
            executorSubscription.unregister();
            setExecutorSubscription(null);
        }
        traversalTargets.clear();
        pendingBreakTargets.clear();
        plannerRunning = false;
        plannerCompleted = false;
        plannerHeartbeatMillis = 0L;
        matchedTargetCount = 0;
        resetExecutorThrottle();
        MyMod.LOG.debug("[ChainSession] Cleared runtime state for player {}, reason={}, queuedTargets={}",
            playerUUID, reason, queuedTargets);
    }
}
