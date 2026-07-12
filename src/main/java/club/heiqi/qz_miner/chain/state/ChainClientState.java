package club.heiqi.qz_miner.chain.state;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.config.CommittedSnapshot;
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
    private volatile long lastObjectGroupRequestedRevision = -1L;
    private volatile long lastObjectGroupAuthoritativeRevision = -1L;
    private volatile boolean hasObjectGroupSyncResult;
    private volatile boolean lastObjectGroupResultAccepted;
    private static final int MAX_PENDING_OBJECT_GROUP_REQUESTS = 8;
    private long objectGroupConnectionToken = -1L;
    private final Map<Long, ObjectGroupRequest> pendingObjectGroupRequests =
            new LinkedHashMap<Long, ObjectGroupRequest>();

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

    /**
     * 开始一个连接级对象组提交序列。
     *
     * <p>重连可能复用同一个本地配置 epoch，因此必须清空上一连接的结果排序水位；旧连接
     * 包本身仍由 {@code ClientConnectionLifecycle} identity gate 丢弃。</p>
     *
     * @param connectionToken 不可复用的连接代际
     * @param committed 同一次 Authority 提交的完整不可变快照
     */
    public void beginObjectGroupSync(long connectionToken, CommittedSnapshot committed) {
        if (connectionToken <= 0L || committed == null) {
            return;
        }
        objectGroupConnectionToken = connectionToken;
        pendingObjectGroupRequests.clear();
        lastObjectGroupRequestedRevision = -1L;
        lastObjectGroupAuthoritativeRevision = -1L;
        hasObjectGroupSyncResult = false;
        lastObjectGroupResultAccepted = false;
        serverObjectGroups = ObjectGroupRuleSet.EMPTY;
        serverObjectGroupRevision = 0L;
        objectGroupSyncAccepted = false;
        registerObjectGroupRequest(connectionToken, committed);
    }

    /** 保存待确认请求的完整快照；只保留最近的有界数量。 */
    public void registerObjectGroupRequest(long connectionToken, CommittedSnapshot committed) {
        if (connectionToken <= 0L || committed == null || connectionToken != objectGroupConnectionToken) {
            return;
        }
        pendingObjectGroupRequests.put(committed.epoch,
                new ObjectGroupRequest(connectionToken, committed));
        while (pendingObjectGroupRequests.size() > MAX_PENDING_OBJECT_GROUP_REQUESTS) {
            Iterator<Long> iterator = pendingObjectGroupRequests.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 在客户端主线程应用对象组确认。
     *
     * <p>结果只接受当前本地提交 epoch，并按
     * {@code (requestedRevision, authoritativeRevision, accepted)} 严格递增；同一
     * authoritative revision 上 accepted 胜过 rejected。这样 rev5 成功后迟到旧拒绝不会
     * 反转状态；若拒绝先到，随后 authoritative revision 更高的成功仍可确认。</p>
     *
     * @return 是否实际发布了新结果
     */
    public boolean applyObjectGroupSyncResult(
            long connectionToken, long requestedRevision, long authoritativeRevision,
            boolean accepted, int groupCount) {
        if (connectionToken <= 0L || requestedRevision < 0L || authoritativeRevision < 0L
                || groupCount < 0 || groupCount > ObjectGroupRuleSet.MAX_GROUPS
                || connectionToken != objectGroupConnectionToken) {
            return false;
        }
        ObjectGroupRequest request = pendingObjectGroupRequests.get(requestedRevision);
        if (request == null || request.connectionToken != connectionToken
                || request.committed.epoch != requestedRevision) {
            return false;
        }
        if (accepted && (authoritativeRevision != requestedRevision
                || request.committed.snapshot.objectGroups.groups().size() != groupCount)) {
            return false;
        }
        if (hasObjectGroupSyncResult && !isStrictlyNewerResult(
                requestedRevision, authoritativeRevision, accepted)) {
            return false;
        }

        lastObjectGroupRequestedRevision = requestedRevision;
        lastObjectGroupAuthoritativeRevision = authoritativeRevision;
        lastObjectGroupResultAccepted = accepted;
        hasObjectGroupSyncResult = true;
        serverObjectGroupRevision = authoritativeRevision;
        objectGroupSyncAccepted = accepted;
        if (accepted) {
            serverObjectGroups = request.committed.snapshot.objectGroups;
        }
        pendingObjectGroupRequests.remove(requestedRevision);
        return true;
    }

    /** 连接生命周期清理：旧连接的请求不能被新连接 ACK 消费。 */
    public void clearObjectGroupSyncPending() {
        pendingObjectGroupRequests.clear();
        objectGroupConnectionToken = -1L;
        hasObjectGroupSyncResult = false;
        lastObjectGroupRequestedRevision = -1L;
        lastObjectGroupAuthoritativeRevision = -1L;
        lastObjectGroupResultAccepted = false;
        serverObjectGroups = ObjectGroupRuleSet.EMPTY;
        serverObjectGroupRevision = 0L;
        objectGroupSyncAccepted = false;
    }

    private static final class ObjectGroupRequest {
        private final long connectionToken;
        private final CommittedSnapshot committed;

        private ObjectGroupRequest(long connectionToken, CommittedSnapshot committed) {
            this.connectionToken = connectionToken;
            this.committed = committed;
        }
    }

    private boolean isStrictlyNewerResult(long requestedRevision, long authoritativeRevision,
            boolean accepted) {
        if (requestedRevision != lastObjectGroupRequestedRevision) {
            return requestedRevision > lastObjectGroupRequestedRevision;
        }
        if (authoritativeRevision != lastObjectGroupAuthoritativeRevision) {
            return authoritativeRevision > lastObjectGroupAuthoritativeRevision;
        }
        return accepted && !lastObjectGroupResultAccepted;
    }

    public boolean isChainActiveDisplay() {
        // 阶段8 块3：删旧 serverChainKeyPressed/serverExecuting（phase 由 ClientPhaseProjection 承载）。
        // HUD 显示权威改为：chainKeyPressed（客户端本地按键）或投影阶段非 IDLE/ARMED 时显示。
        // 此处仅保留客户端本地按键判定，phase 维度由紧凑 HUD provider 据投影控制。
        return chainKeyPressed;
    }
}
