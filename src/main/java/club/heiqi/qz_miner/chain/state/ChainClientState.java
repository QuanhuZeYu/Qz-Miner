package club.heiqi.qz_miner.chain.state;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;

/**
 * 客户端连锁状态。
 */
public class ChainClientState extends AbstractChainModeState {

    private volatile boolean chainKeyPressed;
    private volatile boolean previewActive;
    // 阶段8 块3：删旧 serverChainKeyPressed/serverExecuting/serverExecutionStatus 三字段
    // （phase 已由 ClientPhaseProjection 承载，G2 夺权后 HUD/预览读投影，不再需要旧 executionStatus）。
    private volatile int requestedChainRadius = Config.chainRadius;
    private volatile int requestedChainMaxBlocks = Config.chainMaxBlocks;
    // 阶段8 块3：这三字段保留，写入源从旧八字段包改为新 PacketChainConfigSync（ChainConfigProjectionBridge 下发）。
    private volatile int serverChainRadius = Config.chainRadius;
    private volatile int serverChainMaxBlocks = Config.chainMaxBlocks;
    private volatile int serverMatchedTargetCount;
    private volatile ObjectGroupRuleSet serverObjectGroups = ObjectGroupRuleSet.EMPTY;
    private volatile long serverObjectGroupRevision;
    private volatile boolean objectGroupSyncAccepted;

    public boolean isChainKeyPressed() {
        return chainKeyPressed;
    }

    public void setChainKeyPressed(boolean chainKeyPressed) {
        this.chainKeyPressed = chainKeyPressed;
    }

    public boolean isPreviewActive() {
        return previewActive;
    }

    public void setPreviewActive(boolean previewActive) {
        this.previewActive = previewActive;
    }

    public ChainMode getSelectedMode() {
        return super.getSelectedMode();
    }

    public void setSelectedMode(ChainMode selectedMode) {
        setSelectedModeInternal(selectedMode);
    }

    public ChainSubMode getSelectedSubMode() {
        return super.getSelectedSubMode();
    }

    public void setSelectedSubMode(ChainSubMode selectedSubMode) {
        setSelectedSubModeInternal(selectedSubMode);
    }

    public int getServerChainRadius() {
        return serverChainRadius;
    }

    public int getRequestedChainRadius() {
        return requestedChainRadius;
    }

    public void setRequestedChainRadius(int requestedChainRadius) {
        this.requestedChainRadius = Math.max(1, requestedChainRadius);
    }

    public void setServerChainRadius(int serverChainRadius) {
        this.serverChainRadius = Math.max(1, serverChainRadius);
    }

    public int getServerChainMaxBlocks() {
        return serverChainMaxBlocks;
    }

    public int getRequestedChainMaxBlocks() {
        return requestedChainMaxBlocks;
    }

    public void setRequestedChainMaxBlocks(int requestedChainMaxBlocks) {
        this.requestedChainMaxBlocks = Math.max(1, requestedChainMaxBlocks);
    }

    public void setServerChainMaxBlocks(int serverChainMaxBlocks) {
        this.serverChainMaxBlocks = Math.max(1, serverChainMaxBlocks);
    }

    public int getServerMatchedTargetCount() {
        return serverMatchedTargetCount;
    }

    public void setServerMatchedTargetCount(int serverMatchedTargetCount) {
        this.serverMatchedTargetCount = Math.max(0, serverMatchedTargetCount);
    }

    public ObjectGroupRuleSet getServerObjectGroups() {
        return serverObjectGroups;
    }

    public long getServerObjectGroupRevision() {
        return serverObjectGroupRevision;
    }

    public boolean isObjectGroupSyncAccepted() {
        return objectGroupSyncAccepted;
    }

    public void setServerObjectGroupSync(ObjectGroupRuleSet rules, long revision, boolean accepted) {
        if (rules == null || revision < 0L || revision < serverObjectGroupRevision) {
            return;
        }
        this.serverObjectGroups = rules;
        this.serverObjectGroupRevision = revision;
        this.objectGroupSyncAccepted = accepted;
    }

    /** 保留旧确认快照，仅更新失败状态，避免非法/旧包伪造新规则。 */
    public void markObjectGroupSyncRejected(long revision) {
        if (revision >= 0L && revision >= serverObjectGroupRevision) {
            this.serverObjectGroupRevision = revision;
        }
        this.objectGroupSyncAccepted = false;
    }

    public boolean isChainActiveDisplay() {
        // 阶段8 块3：删旧 serverChainKeyPressed/serverExecuting（phase 由 ClientPhaseProjection 承载）。
        // HUD 显示权威改为：chainKeyPressed（客户端本地按键）或投影阶段非 IDLE/ARMED 时显示。
        // 此处仅保留客户端本地按键判定，phase 维度的显示由 HudOverlay 自行据 ClientPhaseProjection 控制。
        return chainKeyPressed;
    }
}
