package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 连锁预览表现投影的不可变 O(1) header（B1.1 / task-19a）。
 *
 * <p>只承载固定字段的标量快照，<b>不携带任何随目标数增长的集合</b>；目标集合仍由
 * {@link ChainPreviewState} 持有。header 一旦发布即不可变，订阅者可安全持有引用直到下一次回调。</p>
 *
 * <p>身份维度（worldIdentity / lifecycleEpoch / serverRoundId / configRevision /
 * objectGroupRevision / serverGeneration / previewGeneration）参与相等判定：任一变即视为新 header，
 * 订阅者据此重置本地缓存。</p>
 *
 * <p>字段数据源边界（如实标注）：{@code visibleCount} 仍与 {@code matchedCount} 同源（B5.2 只新增
 * {@code executedCount}，不臆造「可见」维度）；{@code executedCount} 来自客户端世界采样 + 位置去重
 * （{@code ChainPreviewExecutionProgress}，同代单调不减，换代/lifecycle/世界切换归零），
 * {@code executionProgressEnabled} 是 {@code clientPreviewExecutionProgress} 的投影位。
 * {@code configRevision} 取自
 * {@code ConfigBootstrap} 提交 epoch（无提交时 0=未接线）。{@code serverRoundId} 客户端当前
 * <b>无协议来源</b>（相位包不含 round 字段），恒 0=未接线；服务端代际失效由 phase 投影的
 * generation 覆盖，真接需协议扩展（下一批评估，本轮不扩协议）。</p>
 */
@SideOnly(Side.CLIENT)
public final class ChainPreviewPresentationHeader {

    private final ChainPhase phase;
    private final int serverGeneration;
    private final int previewGeneration;
    private final boolean previewActive;
    private final boolean previewCompleted;
    private final int scannedCount;
    private final int matchedCount;
    private final int visibleCount;
    private final int executedCount;
    private final ChainPreviewState.TruncationReason truncationReason;
    private final int truncatedCount;
    private final int totalCount;
    private final ChainPreviewState.CancelReason cancelReason;
    private final boolean remoteRequestPending;
    private final int remoteRequestId;
    private final long worldIdentity;
    private final long lifecycleEpoch;
    private final long serverRoundId;
    private final long configRevision;
    private final long objectGroupRevision;
    private final boolean truncationSignalEnabled;
    private final boolean executionProgressEnabled;
    private final long revision;

    /**
     * 构造 header（包级：生产侧经 {@link ChainPreviewPresentationProjection} 发布，不直接 new）。
     */
    ChainPreviewPresentationHeader(
            ChainPhase phase,
            int serverGeneration,
            int previewGeneration,
            boolean previewActive,
            boolean previewCompleted,
            int scannedCount,
            int matchedCount,
            int visibleCount,
            int executedCount,
            ChainPreviewState.TruncationReason truncationReason,
            int truncatedCount,
            int totalCount,
            ChainPreviewState.CancelReason cancelReason,
            boolean remoteRequestPending,
            int remoteRequestId,
            long worldIdentity,
            long lifecycleEpoch,
            long serverRoundId,
            long configRevision,
            long objectGroupRevision,
            boolean truncationSignalEnabled,
            boolean executionProgressEnabled,
            long revision) {
        this.phase = phase == null ? ChainPhase.IDLE : phase;
        this.serverGeneration = serverGeneration;
        this.previewGeneration = previewGeneration;
        this.previewActive = previewActive;
        this.previewCompleted = previewCompleted;
        this.scannedCount = scannedCount;
        this.matchedCount = matchedCount;
        this.visibleCount = visibleCount;
        this.executedCount = executedCount;
        this.truncationReason = truncationReason == null
                ? ChainPreviewState.TruncationReason.NONE : truncationReason;
        this.truncatedCount = truncatedCount;
        this.totalCount = totalCount;
        this.cancelReason = cancelReason == null
                ? ChainPreviewState.CancelReason.NONE : cancelReason;
        this.remoteRequestPending = remoteRequestPending;
        this.remoteRequestId = remoteRequestId;
        this.worldIdentity = worldIdentity;
        this.lifecycleEpoch = lifecycleEpoch;
        this.serverRoundId = serverRoundId;
        this.configRevision = configRevision;
        this.objectGroupRevision = objectGroupRevision;
        this.truncationSignalEnabled = truncationSignalEnabled;
        this.executionProgressEnabled = executionProgressEnabled;
        this.revision = revision;
    }

    /** @return 服务端状态机投影阶段 */
    public ChainPhase getPhase() {
        return phase;
    }

    /** @return 服务端状态机代际（ClientPhaseProjection） */
    public int getServerGeneration() {
        return serverGeneration;
    }

    /** @return 客户端预览代际（ChainPreviewState） */
    public int getPreviewGeneration() {
        return previewGeneration;
    }

    /** @return 预览是否处于活动态 */
    public boolean isPreviewActive() {
        return previewActive;
    }

    /** @return 预览本代是否已完成（扫描侧收尾） */
    public boolean isPreviewCompleted() {
        return previewCompleted;
    }

    /** @return 已扫描计数 */
    public int getScannedCount() {
        return scannedCount;
    }

    /** @return 已匹配（已接收）目标数 */
    public int getMatchedCount() {
        return matchedCount;
    }

    /** @return 可见目标数；仍与 matchedCount 同源（本类不臆造「可见」维度） */
    public int getVisibleCount() {
        return visibleCount;
    }

    /**
     * @return 已执行目标数（客户端世界采样 + 位置去重）：同一预览代内单调不减，
     *         换代 / lifecycle / 世界切换归零；开关关闭或无活动代时恒 0
     */
    public int getExecutedCount() {
        return executedCount;
    }

    /** @return 截断原因；NONE 表示无截断 */
    public ChainPreviewState.TruncationReason getTruncationReason() {
        return truncationReason;
    }

    /** @return 已知被截断目标数；0 表示未截断或数量未知（只有下界，配合 reason 解释） */
    public int getTruncatedCount() {
        return truncatedCount;
    }

    /** @return 本代已知目标总数（含被截断） */
    public int getTotalCount() {
        return totalCount;
    }

    /** @return 失败取消原因；NONE 表示未因失败取消 */
    public ChainPreviewState.CancelReason getCancelReason() {
        return cancelReason;
    }

    /** @return 是否存在在途远端预览请求 */
    public boolean isRemoteRequestPending() {
        return remoteRequestPending;
    }

    /** @return 在途远端预览请求 id；无在途请求时为 0 */
    public int getRemoteRequestId() {
        return remoteRequestId;
    }

    /** @return 世界身份（装配侧提供，0 表示未接线） */
    public long getWorldIdentity() {
        return worldIdentity;
    }

    /** @return 生命周期 epoch（装配侧提供，0 表示未接线） */
    public long getLifecycleEpoch() {
        return lifecycleEpoch;
    }

    /** @return 服务端 round id（装配侧提供，0 表示未接线） */
    public long getServerRoundId() {
        return serverRoundId;
    }

    /** @return 配置 revision（装配侧提供，0 表示未接线） */
    public long getConfigRevision() {
        return configRevision;
    }

    /** @return 对象组 revision（来自 ChainClientState.getServerObjectGroupRevision） */
    public long getObjectGroupRevision() {
        return objectGroupRevision;
    }

    /**
     * @return 截断可见信号开关（clientPreviewTruncationSignal 的投影位，供 HUD 零分配读取；
     *         装配未接线时恒 false = 不显示截断行）
     */
    public boolean isTruncationSignalEnabled() {
        return truncationSignalEnabled;
    }

    /**
     * @return 执行进度开关（clientPreviewExecutionProgress 的投影位，供 HUD 零分配读取；
     *         false = 不采样不计数，executedCount 恒 0）
     */
    public boolean isExecutionProgressEnabled() {
        return executionProgressEnabled;
    }

    /** @return header 自身单调递增 revision（每次发布 +1） */
    public long getRevision() {
        return revision;
    }

    /**
     * @param other 候选 header
     * @return 除 {@link #getRevision()} 外全部字段是否相等（含六个身份维度）
     */
    boolean sameContent(ChainPreviewPresentationHeader other) {
        if (other == null) {
            return false;
        }
        return serverGeneration == other.serverGeneration
            && previewGeneration == other.previewGeneration
            && previewActive == other.previewActive
            && previewCompleted == other.previewCompleted
            && scannedCount == other.scannedCount
            && matchedCount == other.matchedCount
            && visibleCount == other.visibleCount
            && executedCount == other.executedCount
            && truncatedCount == other.truncatedCount
            && totalCount == other.totalCount
            && remoteRequestPending == other.remoteRequestPending
            && remoteRequestId == other.remoteRequestId
            && worldIdentity == other.worldIdentity
            && lifecycleEpoch == other.lifecycleEpoch
            && serverRoundId == other.serverRoundId
            && configRevision == other.configRevision
            && objectGroupRevision == other.objectGroupRevision
            && truncationSignalEnabled == other.truncationSignalEnabled
            && executionProgressEnabled == other.executionProgressEnabled
            && phase == other.phase
            && truncationReason == other.truncationReason
            && cancelReason == other.cancelReason;
    }

    /** @return 替换自身 revision 的副本（包级：仅投影发布路径使用） */
    ChainPreviewPresentationHeader withRevision(long nextRevision) {
        return new ChainPreviewPresentationHeader(
            phase,
            serverGeneration,
            previewGeneration,
            previewActive,
            previewCompleted,
            scannedCount,
            matchedCount,
            visibleCount,
            executedCount,
            truncationReason,
            truncatedCount,
            totalCount,
            cancelReason,
            remoteRequestPending,
            remoteRequestId,
            worldIdentity,
            lifecycleEpoch,
            serverRoundId,
            configRevision,
            objectGroupRevision,
            truncationSignalEnabled,
            executionProgressEnabled,
            nextRevision);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChainPreviewPresentationHeader)) {
            return false;
        }
        ChainPreviewPresentationHeader that = (ChainPreviewPresentationHeader) other;
        return revision == that.revision && sameContent(that);
    }

    @Override
    public int hashCode() {
        int result = phase.hashCode();
        result = 31 * result + serverGeneration;
        result = 31 * result + previewGeneration;
        result = 31 * result + (previewActive ? 1 : 0);
        result = 31 * result + (previewCompleted ? 1 : 0);
        result = 31 * result + scannedCount;
        result = 31 * result + matchedCount;
        result = 31 * result + visibleCount;
        result = 31 * result + executedCount;
        result = 31 * result + truncationReason.hashCode();
        result = 31 * result + truncatedCount;
        result = 31 * result + totalCount;
        result = 31 * result + cancelReason.hashCode();
        result = 31 * result + (remoteRequestPending ? 1 : 0);
        result = 31 * result + remoteRequestId;
        result = 31 * result + (int) (worldIdentity ^ (worldIdentity >>> 32));
        result = 31 * result + (int) (lifecycleEpoch ^ (lifecycleEpoch >>> 32));
        result = 31 * result + (int) (serverRoundId ^ (serverRoundId >>> 32));
        result = 31 * result + (int) (configRevision ^ (configRevision >>> 32));
        result = 31 * result + (int) (objectGroupRevision ^ (objectGroupRevision >>> 32));
        result = 31 * result + (truncationSignalEnabled ? 1 : 0);
        result = 31 * result + (executionProgressEnabled ? 1 : 0);
        result = 31 * result + (int) (revision ^ (revision >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "ChainPreviewPresentationHeader{revision=" + revision
            + ", phase=" + phase
            + ", serverGen=" + serverGeneration
            + ", previewGen=" + previewGeneration
            + ", active=" + previewActive
            + ", completed=" + previewCompleted
            + ", scanned=" + scannedCount
            + ", matched=" + matchedCount
            + ", visible=" + visibleCount
            + ", executed=" + executedCount
            + ", truncated=" + truncationReason + "/" + truncatedCount
            + ", total=" + totalCount
            + ", remotePending=" + remoteRequestPending + "#" + remoteRequestId
            + ", cancel=" + cancelReason
            + ", world=" + worldIdentity
            + ", lifecycle=" + lifecycleEpoch
            + ", round=" + serverRoundId
            + ", configRev=" + configRevision
            + ", objectGroupRev=" + objectGroupRevision
            + ", truncationSignal=" + truncationSignalEnabled
            + ", executionProgress=" + executionProgressEnabled
            + '}';
    }
}
