package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;

/**
 * 服务端玩家连锁状态。
 *
 * <p>掉落缓冲独立于运行态；退休状态只保留待交付资产的归属。
 * 释放失败计数和下次重试 tick 只控制诊断与重试节奏，不改变资产。</p>
 */
public class ChainPlayerState extends AbstractChainModeState {

    private final UUID playerUUID;
    private final ChainPlayerDropBuffer dropBuffer;
    private boolean pendingRemoval;
    private volatile boolean chainKeyPressed;
    private volatile ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private volatile int requestedChainRadius = -1;
    private volatile int requestedChainMaxBlocks = -1;
    private volatile TunnelDirectionSource acceptedTunnelDirectionSource = TunnelDirectionSource.legacyDefault();
    private volatile PendingTunnelHit pendingTunnelHit;
    private volatile ObjectGroupRuleSet objectGroupRules = ObjectGroupRuleSet.EMPTY;
    private volatile long objectGroupRevision;
    private volatile ChainSession session;
    private volatile CuboidSelection cuboidSelection = CuboidSelection.empty();

    /** 连续失败数用于主线程重试调度与摘要诊断，资产保留在独立缓冲。 */
    private volatile int dropReleaseConsecutiveFailures;
    private long nextDropReleaseTick;

    /**
     * 捕获原版起点方块掉落的一次性标志，独立于执行窗口与状态机阶段。
     * BreakEvent 与 HarvestDropsEvent 在同 tick 同步发生，规划登记稍后经 drain 执行；
     * 规划入口先 arm，collector 按同 tick 消费一次，使起点掉落进入玩家缓冲。
     */
    private volatile boolean seedDropCaptureArmed;

    /** arm 时的服务端 tick 戳，配合 {@link #seedDropCaptureArmed} 做同 tick 戳校验防跨 tick 陈旧标志。 */
    private volatile long seedArmTick;

    public ChainPlayerState(UUID playerUUID) {
        this(playerUUID, new ChainPlayerDropBuffer());
    }

    /** 同一服务端内重连时转移资产归属，运行态与配置重新初始化。 */
    ChainPlayerState(UUID playerUUID, ChainPlayerDropBuffer dropBuffer) {
        this.playerUUID = playerUUID;
        this.dropBuffer = dropBuffer;
    }

    boolean isPendingRemoval() {
        return pendingRemoval;
    }

    /** 退休 owner 释放会话，保留玩家标识与未交付缓冲供后续领取。 */
    void retireRuntimeState(String reason) {
        clearRuntimeState(reason);
        setChainKeyPressed(false);
        seedDropCaptureArmed = false;
        clearObjectGroupRules();
        clearCuboidSelection();
        pendingRemoval = true;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public boolean isChainKeyPressed() {
        return chainKeyPressed;
    }

    public void setChainKeyPressed(boolean chainKeyPressed) {
        if (this.chainKeyPressed != chainKeyPressed) {
            MyMod.LOG.debug("[ChainState] Player {} chainKeyPressed {} -> {}", playerUUID, this.chainKeyPressed, chainKeyPressed);
        }
        this.chainKeyPressed = chainKeyPressed;
        if (!chainKeyPressed) {
            clearPendingTunnelHit();
        }
    }

    public boolean isExecuting() {
        return executionStatus != ChainExecutionStatus.IDLE;
    }

    public void setExecuting(boolean executing) {
        this.executionStatus = executing ? ChainExecutionStatus.RUNNING : ChainExecutionStatus.IDLE;
    }

    public ChainExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(ChainExecutionStatus executionStatus) {
        setExecutionStatus(executionStatus, "unspecified");
    }

    public void setExecutionStatus(ChainExecutionStatus executionStatus, String reason) {
        ChainExecutionStatus newStatus = executionStatus == null ? ChainExecutionStatus.IDLE : executionStatus;
        if (this.executionStatus != newStatus) {
            int pendingDropsCount = dropBuffer.size();
            MyMod.LOG.debug(
                "[ChainState] Player {} executionStatus {} -> {} reason={} pendingDrops={}",
                playerUUID,
                this.executionStatus,
                newStatus,
                reason,
                pendingDropsCount);
        }
        this.executionStatus = newStatus;
    }

    public ChainMode getSelectedMode() {
        return super.getSelectedMode();
    }

    public void setSelectedMode(ChainMode selectedMode) {
        ChainMode previousMode = getSelectedMode();
        ChainMode newMode = club.heiqi.qz_miner.chain.mode.ChainModeRegistry.resolveMode(selectedMode);
        if (previousMode != newMode) {
            MyMod.LOG.debug("[ChainState] Player {} selectedMode {} -> {}", playerUUID, previousMode, newMode);
        }
        setSelectedModeInternal(newMode);
        if (previousMode != newMode) {
            clearPendingTunnelHit();
        }
    }

    /**
     * 获取当前主模式下的子模式。
     *
     * @return 当前子模式
     */
    public ChainSubMode getSelectedSubMode() {
        return super.getSelectedSubMode();
    }

    /**
     * 设置当前主模式下的子模式。
     *
     * @param selectedSubMode 当前子模式
     */
    public void setSelectedSubMode(ChainSubMode selectedSubMode) {
        ChainSubMode previousSubMode = getSelectedSubMode();
        ChainSubMode newSubMode = club.heiqi.qz_miner.chain.mode.ChainModeRegistry.resolveSubMode(getSelectedMode(), selectedSubMode);
        if (previousSubMode != newSubMode) {
            MyMod.LOG.debug("[ChainState] Player {} selectedSubMode {} -> {}", playerUUID, previousSubMode, newSubMode);
        }
        setSelectedSubModeInternal(selectedSubMode);
        if (previousSubMode != newSubMode) {
            clearPendingTunnelHit();
        }
    }

    public ChainSession getSession() {
        return session;
    }


    public boolean isSessionActive(ChainSession session) {
        return session != null && this.session == session;
    }

    /**
     * 获取玩家级掉落缓冲。
     *
     * @return 掉落缓冲
     */
    public ChainPlayerDropBuffer getDropBuffer() {
        return dropBuffer;
    }

    public int getRequestedChainRadius() {
        return requestedChainRadius;
    }

    public void setRequestedChainRadius(int requestedChainRadius) {
        this.requestedChainRadius = requestedChainRadius;
    }

    public int getRequestedChainMaxBlocks() {
        return requestedChainMaxBlocks;
    }

    public void setRequestedChainMaxBlocks(int requestedChainMaxBlocks) {
        this.requestedChainMaxBlocks = requestedChainMaxBlocks;
    }

    /** @return 当前服务端上限约束下可兑现的 accepted maxBlocks */
    public int resolveAcceptedChainMaxBlocks() {
        return requestedChainMaxBlocks > 0
                ? Math.min(Config.chainMaxBlocks, requestedChainMaxBlocks)
                : Config.chainMaxBlocks;
    }

    /** 在服务端主线程原子覆盖一个选择点。 */
    public synchronized CuboidSelection.Update selectCuboidPoint(
            int pointIndex, int dimensionId, int x, int y, int z) {
        CuboidSelection.Update update = cuboidSelection.select(
                pointIndex, dimensionId, x, y, z, resolveAcceptedChainMaxBlocks());
        if (update.isAccepted()) cuboidSelection = update.getSelection();
        return update;
    }

    /** @return 当前服务端确认的不可变选择快照 */
    public CuboidSelection getCuboidSelection() {
        return cuboidSelection;
    }

    /** 生命周期边界清除跨轮次选择。 */
    public synchronized CuboidSelection clearCuboidSelection() {
        cuboidSelection = cuboidSelection.clearForAuthority();
        return cuboidSelection;
    }

    /** 配置下调后整体失效超限选区，并保留客户端可排序 revision。 */
    public synchronized CuboidSelection invalidateOversizedCuboidSelection() {
        club.heiqi.qz_miner.chain.selection.CuboidBounds bounds = cuboidSelection.bounds();
        if (bounds == null || bounds.fitsWithin(resolveAcceptedChainMaxBlocks())) return null;
        cuboidSelection = cuboidSelection.clearForAuthority();
        return cuboidSelection;
    }

    /** @return 服务端主线程最近一次整包接受的隧道方向来源 */
    public TunnelDirectionSource getAcceptedTunnelDirectionSource() {
        return acceptedTunnelDirectionSource;
    }

    /** 玩家连接生命周期清理时回落 legacy LOOK。 */
    public void resetAcceptedTunnelDirectionSource() {
        acceptedTunnelDirectionSource = TunnelDirectionSource.legacyDefault();
    }

    /** 服务端主线程原子发布已接受的半径、上限与方向来源。 */
    public void setAcceptedChainConfig(int radius, int maxBlocks, TunnelDirectionSource source) {
        if (radius <= 0 || maxBlocks <= 0 || source == null) {
            throw new IllegalArgumentException("accepted chain config must be valid");
        }
        requestedChainRadius = radius;
        requestedChainMaxBlocks = maxBlocks;
        acceptedTunnelDirectionSource = source;
    }

    /** latest-wins 记录左键命中面；只保存纯值。 */
    public void recordPendingTunnelHit(int dimensionId, int x, int y, int z, int face) {
        pendingTunnelHit = new PendingTunnelHit(dimensionId, x, y, z, face);
    }

    /**
     * 一次性消费 pending。任何坐标/维度失配同样立即丢弃并返回 fallback。
     *
     * @param fallbackFace BreakEvent 时冻结的 look face
     * @return 匹配时为命中外法线的 opposite，否则为 fallback
     */
    public int consumePendingTunnelFace(int dimensionId, int x, int y, int z, int fallbackFace) {
        PendingTunnelHit pending = pendingTunnelHit;
        pendingTunnelHit = null;
        if (pending == null || !pending.matches(dimensionId, x, y, z)) {
            return club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection.normalizeFace(fallbackFace);
        }
        return club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection.resolveHitFaceOrLook(
                pending.face, fallbackFace);
    }

    /** 清除尚未消费的命中面。 */
    public void clearPendingTunnelHit() {
        pendingTunnelHit = null;
    }

    /** @return 当前是否存在 pending；仅供诊断与测试 */
    public boolean hasPendingTunnelHit() {
        return pendingTunnelHit != null;
    }

    /** @return 当前玩家服务端已接受的不可变对象组规则 */
    public ObjectGroupRuleSet getObjectGroupRules() {
        return objectGroupRules;
    }

    /** @return 当前已接受对象组配置 revision */
    public long getObjectGroupRevision() {
        return objectGroupRevision;
    }

    /** 在服务端主线程整包接受后替换玩家对象组规则。 */
    public void setObjectGroupRules(ObjectGroupRuleSet rules, long revision) {
        if (rules == null || revision < 0L) {
            throw new IllegalArgumentException("rules/revision must be valid");
        }
        this.objectGroupRules = rules;
        this.objectGroupRevision = revision;
    }

    /** 统一移除玩家状态时清理已接受的对象组配置。 */
    public void clearObjectGroupRules() {
        this.objectGroupRules = ObjectGroupRuleSet.EMPTY;
        this.objectGroupRevision = 0L;
    }

    public void setSession(ChainSession session) {
        ChainSession previousSession = this.session;
        if (previousSession != null && previousSession != session) {
            GregTechCableSessionState.clear(previousSession);
        }
        if (this.session == null && session != null) {
            MyMod.LOG.debug("[ChainState] Player {} session attached mode={} origin=({}, {}, {})",
                playerUUID,
                session.getRequest().getMode(),
                session.getRequest().getOrigin().getX(),
                session.getRequest().getOrigin().getY(),
                session.getRequest().getOrigin().getZ());
        } else if (this.session != null && session == null) {
            MyMod.LOG.debug("[ChainState] Player {} session cleared", playerUUID);
        }
        this.session = session;
    }

    public void clearSession() {
        setSession(null);
    }

    public void clearRuntimeState() {
        clearRuntimeState("unspecified");
    }

    public void clearRuntimeState(String reason) {
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        ChainSession currentSession = this.session;
        if (currentSession != null) {
            GregTechCableSessionState.clear(currentSession);
            currentSession.clearRuntimeState(reason);
            if (this.session == currentSession) {
                clearSession();
            }
        }
        // 重置运行态的重试节奏，待交付资产继续由缓冲持有。
        resetDropReleaseFailure();
        clearPendingTunnelHit();
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}", playerUUID, reason);
    }


    /**
     * Arm 起点方块掉落捕获一次性标志。
     *
     * <p>仅由 {@code ChainPlanner.onBlockBreak} 在 publish {@link club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved}
     * 后立即调用，记录 arm 时的服务端 tick 戳。collector 侧 {@link #consumeSeedDropCaptureIfArmed(long)}
     * 在同 tick 戳消费一次即清零。</p>
     *
     * <p>该标志独立记录原版同步收获窗口，由收集器按同 tick 一次性消费。</p>
     *
     * @param currentServerTick arm 时的服务端 tick 计数
     */
    public void armSeedDropCapture(long currentServerTick) {
        this.seedDropCaptureArmed = true;
        this.seedArmTick = currentServerTick;
        MyMod.LOG.debug("[ChainState] Player {} seedDropCapture armed at tick {}", playerUUID, currentServerTick);
    }

    /**
     * 消费起点方块掉落捕获标志：仅当 armed 且 tick 戳相同才返回 true 并清零（一次性）。
     *
     * <p>由 {@code ChainDropCollector.onHarvestDrops} 调用；仅相同 tick 的首次消费清零，
     * 延迟到后续 tick 的事件按过期标志处理。</p>
     *
     * @param currentServerTick 消费时的服务端 tick 计数
     * @return 仅当 armed 且 tick 相同时返回 true 并清零；否则 false（不动状态）
     */
    public boolean consumeSeedDropCaptureIfArmed(long currentServerTick) {
        if (seedDropCaptureArmed && seedArmTick == currentServerTick) {
            seedDropCaptureArmed = false;
            MyMod.LOG.debug("[ChainState] Player {} seedDropCapture consumed at tick {}", playerUUID, currentServerTick);
            return true;
        }
        return false;
    }

    /**
     * 累加 world-tick 掉落释放连续失败计数并返回累加后的值。
     *
     * <p>所有释放位置都失败后累加，仅用于重试与诊断；计数在 int 上界饱和。</p>
     *
     * @return 累加后的连续失败次数
     */
    public int incrementDropReleaseFailure() {
        if (dropReleaseConsecutiveFailures < Integer.MAX_VALUE) dropReleaseConsecutiveFailures++;
        return dropReleaseConsecutiveFailures;
    }

    /**
     * 重置 world-tick 掉落释放连续失败计数为 0。
     *
     * <p>释放成功任一级降级后调用；{@link #clearRuntimeState(String)} 生命周期收口时也调用（守生命周期收口）。</p>
     */
    public void resetDropReleaseFailure() {
        dropReleaseConsecutiveFailures = 0;
        nextDropReleaseTick = 0L;
    }

    /** 主线程按服务端 tick 统一调度各维度的释放重试。 */
    public boolean isDropReleaseReady(long serverTick) {
        return serverTick >= nextDropReleaseTick;
    }

    public void deferDropReleaseUntil(long serverTick) {
        nextDropReleaseTick = serverTick;
    }

    /** 仅含维度、坐标与方向纯值的一次性左键命中快照。 */
    private static final class PendingTunnelHit {
        private final int dimensionId;
        private final int x;
        private final int y;
        private final int z;
        private final int face;

        private PendingTunnelHit(int dimensionId, int x, int y, int z, int face) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
        }

        private boolean matches(int dimensionId, int x, int y, int z) {
            return this.dimensionId == dimensionId && this.x == x && this.y == y && this.z == z;
        }
    }
}
