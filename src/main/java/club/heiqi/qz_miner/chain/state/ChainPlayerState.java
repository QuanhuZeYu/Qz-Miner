package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 服务端玩家连锁状态。
 *
 * <p>字段 {@link #dropReleaseConsecutiveFailures}：掉落释放连续失败计数，
 * world-tick 释放路径用，累计超 {@code DROP_RELEASE_MAX_RETRIES} 上限触发 discard 兜底
 * （守 NORTH_STAR 信条四四级降级链终点）；{@link #clearRuntimeState(String)} 收口清零，
 * 防跨生命周期残留脏计数（守 I7）。</p>
 */
public class ChainPlayerState extends AbstractChainModeState {

    private final UUID playerUUID;
    private final ChainPlayerDropBuffer dropBuffer = new ChainPlayerDropBuffer();
    private volatile boolean chainKeyPressed;
    private volatile ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private volatile int requestedChainRadius = -1;
    private volatile int requestedChainMaxBlocks = -1;
    private volatile ChainSession session;

    /**
     * world-tick 掉落释放连续失败计数（守信条四四级降级链终点）。
     *
     * <p>由 {@link club.heiqi.qz_miner.chain.executor.ChainDropCollector#onWorldTick} 释放路径在
     * 全链（当前位置→重生/出生点→已记忆兜底）均失败时 {@link #incrementDropReleaseFailure()} 累加，
     * 达 {@code DROP_RELEASE_MAX_RETRIES} 上限触发 {@code ChainDropReleaseHelper.discard} 兜底丢弃。
     * 释放成功任一级即 {@link #resetDropReleaseFailure()} 清零。</p>
     *
     * <p>独立于 {@link #executionStatus}/phase/generation，不复用状态机写入口（类比
     * {@link #seedDropCaptureArmed} 独立字段自行管理）。{@link #clearRuntimeState(String)} 收口清零，
     * 守 I7 生命周期收口，防跨玩家/跨会话残留脏计数。</p>
     */
    private volatile int dropReleaseConsecutiveFailures;

    /**
     * 起点方块掉落捕获一次性 armed 标志。
     *
     * <p>背景：Forge 1.7.10 {@code ItemInWorldManager.tryHarvestBlock} 在同 tick 内同步执行
     * {@code BreakEvent}（ChainPlanner 看到）→ {@code removeBlock} → {@code harvestBlock}
     * → {@code HarvestDropsEvent}（ChainDropCollector 看到）。但 collector 执行窗口要等
     * {@code onPlanCompleted} 下 tick drain 才开（{@code setExecutionWindow(true)}），
     * 早于窗口的起点方块掉落会被 {@code isExecuting()} 守卫拦截止，结果走 vanilla 未收。</p>
     *
     * <p>方案：ChainPlanner.onBlockBreak publish {@link club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved}
     * 后立即 {@link #armSeedDropCapture(long)} arm 一次；collector 守卫扩展为
     * {@code (isExecuting() || consumeSeedDropCaptureIfArmed(tick))}，armed 时也收一次。</p>
     *
     * <p>守 NORTH_STAR I10（状态机唯一写权威）：本字段独立于 {@link #executionStatus}/phase/generation，
     * 不复用 {@link #setExecuting(boolean)} 写入口；{@code volatile} 保证主线程 publish 侧 arm 与
     * 主线程 collector 侧 consume 的可见性（同 tick 同步链路，无跨线程竞争）。</p>
     */
    private volatile boolean seedDropCaptureArmed;

    /** arm 时的服务端 tick 戳，配合 {@link #seedDropCaptureArmed} 做同 tick 戳校验防跨 tick 陈旧标志。 */
    private volatile long seedArmTick;

    public ChainPlayerState(UUID playerUUID) {
        this.playerUUID = playerUUID;
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
    }

    public ChainSession getSession() {
        return session;
    }

    // 阶段8 块3：删旧 getMatchedTargetCount/getPendingBreakTargetCount/hasPlannerSubscription
    // （ChainSession 委托方法已删，这些读取链已断；syncPlayerState 已删无调用方）。

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
        // 守 I7：掉落释放失败计数随运行时状态一并清零，防跨生命周期残留脏计数
        // （玩家重生/切维度/克隆后下一轮释放从 0 起算，避免误触发 discard 兜底）。
        resetDropReleaseFailure();
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}", playerUUID, reason);
    }

    // 阶段8 块3：删旧 stopExecutionPreservingDrops（三层死代码：ChainRuntimeState/ChainSession/ChainPlayerState）。
    // 新链路无外部调用方（G1 掉落窗口由 ChainDropCollector + executionStatus 守，不依赖 stopExecution 收口）。

    /**
     * Arm 起点方块掉落捕获一次性标志。
     *
     * <p>仅由 {@code ChainPlanner.onBlockBreak} 在 publish {@link club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved}
     * 后立即调用，记录 arm 时的服务端 tick 戳。collector 侧 {@link #consumeSeedDropCaptureIfArmed(long)}
     * 在同 tick 戳消费一次即清零。</p>
     *
     * <p>守 I10：不复用 {@link #setExecuting(boolean)}/{@link #setExecutionStatus} 写入口，
     * 独立字段，状态机唯一写权威不受影响。</p>
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
     * <p>由 {@code ChainDropCollector.onHarvestDrops} 守卫调用。同 tick 戳校验是兜底：
     * 若 HarvestDropsEvent 不是同 tick 触发（例如延迟触发），下一个 tick 戳不同则返回 false，
     * 陈旧标志自然失效，不需主动清零。</p>
     *
     * <p>一次性语义：调用一次即清零，执行链 worker 同 tick 处理多次 或 onPlanCompleted 后
     * collector 再次开窗口 都不会因为残留 armed 误判。</p>
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
     * <p>由 {@link club.heiqi.qz_miner.chain.executor.ChainDropCollector#onWorldTick} 在四级降级链
     * 全部失败后调用，达 {@code DROP_RELEASE_MAX_RETRIES} 上限即由调用方触发
     * {@code ChainDropReleaseHelper.discard} 兜底丢弃。</p>
     *
     * @return 累加后的连续失败次数
     */
    public int incrementDropReleaseFailure() {
        return ++dropReleaseConsecutiveFailures;
    }

    /**
     * 重置 world-tick 掉落释放连续失败计数为 0。
     *
     * <p>释放成功任一级降级后调用；{@link #clearRuntimeState(String)} 生命周期收口时也调用（守 I7）。</p>
     */
    public void resetDropReleaseFailure() {
        dropReleaseConsecutiveFailures = 0;
    }
}
