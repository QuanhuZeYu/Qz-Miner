package club.heiqi.qz_miner.chain.client.projection;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 连锁预览表现投影单一事实源（B1.1 / task-19a）。
 *
 * <p>把 phase / generation / scanned / matched / visible / truncated(reason+count) /
 * remoteRequest(状态+id) / cancelReason 收口为一份不可变 O(1) header，并对外提供唯一订阅入口；
 * HUD / 截断提示 / 远端失败等消费方改为订阅本投影，不再各自旁路读
 * {@link ChainPreviewState}。</p>
 *
 * <h3>线程契约</h3>
 * <ul>
 *   <li>{@link #project} / {@link #subscribe} / {@link #unsubscribe} 契约上都在客户端主线程
 *       （{@code ClientTickEvent.START} 的 drain 内）；{@link #bindMainThread(Thread)} 做软校验，
 *       违反只记 warn，不抛异常（与 {@code ChainEventBus.drain} 的软校验一致）。</li>
 *   <li>回调在 {@link #project} 内<b>同步</b>执行：订阅者可安全持有 header 引用直到下次回调。</li>
 *   <li>单个订阅者抛 {@link RuntimeException} 被隔离并计 warn，不中断其他订阅者，不摘除订阅。</li>
 * </ul>
 *
 * <h3>失效契约（六个身份维度）</h3>
 * <p>worldIdentity / lifecycleEpoch / serverRoundId / configRevision / objectGroupRevision /
 * serverGeneration（以及 previewGeneration）任一变化即视为新 header：{@link #project} 发布新
 * revision 并通知全部订阅者，订阅者据此重置本地缓存。全部字段不变时不发布（零通知）。</p>
 *
 * <h3>生命周期</h3>
 * <p>断线/世界卸载时由装配点调用 {@link #clear()}（丢弃当前 header、revision 水位重置、订阅者保留），
 * 使 HUD 自动降级并在重连后的下一 tick 恢复；{@link #clearSubscriptions()} 连通订阅者一起摘除；
 * {@link Subscription#unsubscribe()} 幂等。{@link #uninstall} 仅用于测试/进程退出——生产 cleanup
 * <b>不</b> uninstall（ClientProxy.init 只在客户端启动执行一次，uninstall 会让重连后的 HUD 永久读不到
 * 投影实例）。</p>
 */
@SideOnly(Side.CLIENT)
public final class ChainPreviewPresentationProjection {

    /** 订阅者接口：仅在发布线程（客户端主线程 drain 内）同步回调。 */
    public interface Listener {

        /**
         * @param header 新发布的不可变 header，非 null，可安全持有
         */
        void onHeaderChanged(ChainPreviewPresentationHeader header);
    }

    /** 订阅句柄；{@link #unsubscribe()} 幂等。 */
    public interface Subscription {

        /** 退订；重复调用为无操作。 */
        void unsubscribe();
    }

    /** 装配单例（客户端主线程 install/uninstall；HUD 与诊断订阅者的 O(1) 访问点）。 */
    private static volatile ChainPreviewPresentationProjection installed;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile ChainPreviewPresentationHeader currentHeader;
    /** 发布 revision 水位：原子自增，保证 checkThread 仅告警时也不出现重复 revision / 丢通知。 */
    private final AtomicLong publishedRevision = new AtomicLong();
    private volatile Thread mainThread;

    /**
     * @return 当前装配的投影实例；未装配返回 null（HUD 必须按 null 降级）
     */
    public static ChainPreviewPresentationProjection installed() {
        return installed;
    }

    /**
     * 装配单例（客户端主线程）。
     *
     * @param projection 投影实例，null 视为卸载
     * @return 之前的实例（可能为 null）
     */
    public static ChainPreviewPresentationProjection install(ChainPreviewPresentationProjection projection) {
        ChainPreviewPresentationProjection previous = installed;
        installed = projection;
        return previous;
    }

    /**
     * 卸载单例：仅当当前实例与参数一致时清除，避免覆盖新装配的实例。
     *
     * @param projection 期望卸载的实例
     * @return 之前的实例（可能为 null）
     */
    public static ChainPreviewPresentationProjection uninstall(ChainPreviewPresentationProjection projection) {
        ChainPreviewPresentationProjection previous = installed;
        if (previous == projection) {
            installed = null;
        }
        return previous;
    }

    /** 绑定主线程引用，供软校验；null 表示不校验。 */
    public void bindMainThread(Thread thread) {
        this.mainThread = thread;
    }

    /**
     * 订阅 header 变化；订阅后若已有当前 header，调用方可直接读 {@link #currentHeader()} 补齐。
     *
     * @param listener 订阅者，非 null
     * @return 退订句柄
     */
    public Subscription subscribe(final Listener listener) {
        checkThread("subscribe");
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
        listeners.addIfAbsent(listener);
        return new Subscription() {
            private volatile boolean active = true;

            @Override
            public void unsubscribe() {
                if (!active) {
                    return;
                }
                active = false;
                checkThread("unsubscribe");
                listeners.remove(listener);
            }
        };
    }

    /** @return 当前订阅者数量 */
    public int getListenerCount() {
        return listeners.size();
    }

    /** @return 当前 header；尚未发布时为 null（O(1)） */
    public ChainPreviewPresentationHeader currentHeader() {
        return currentHeader;
    }

    /**
     * 发布候选 header：与当前 header 内容（含六个身份维度）不同才发布新 revision 并通知订阅者。
     *
     * @param candidate 候选 header，null 视为无操作
     * @return 发布后的当前 header（内容未变时返回原实例）
     */
    public ChainPreviewPresentationHeader project(ChainPreviewPresentationHeader candidate) {
        checkThread("project");
        if (candidate == null) {
            return currentHeader;
        }
        ChainPreviewPresentationHeader existing = currentHeader;
        if (existing != null && existing.sameContent(candidate)) {
            return existing;
        }
        ChainPreviewPresentationHeader published = candidate.withRevision(publishedRevision.incrementAndGet());
        currentHeader = published;
        notifyListeners(published);
        return published;
    }

    /**
     * 便捷采样入口：从预览只读访问器与阶段投影采样并发布（装配点每客户端 tick 调一次即可）。
     *
     * <p>纯 O(1) 读取；参数允许为 null（字段回落安全默认），便于 headless 与分阶段装配。</p>
     *
     * @param previewState 预览状态（只读访问器）
     * @param previewController 预览控制器（远端请求在途状态）
     * @param phaseProjection 客户端阶段投影
     * @param worldIdentity 世界身份（装配侧提供，未接线传 0）
     * @param lifecycleEpoch 生命周期 epoch（装配侧提供，未接线传 0）
     * @param serverRoundId 服务端 round id（装配侧提供，未接线传 0）
     * @param configRevision 配置 revision（装配侧提供，未接线传 0）
     * @param objectGroupRevision 对象组 revision（来自 ChainClientState）
     * @param truncationSignalEnabled 截断可见开关（装配点从 settings 快照取值；未接线传 false）
     * @return 发布后的当前 header
     */
    public ChainPreviewPresentationHeader sampleAndPublish(
            ChainPreviewState previewState,
            ChainPreviewController previewController,
            ClientPhaseProjection phaseProjection,
            long worldIdentity,
            long lifecycleEpoch,
            long serverRoundId,
            long configRevision,
            long objectGroupRevision,
            boolean truncationSignalEnabled) {
        return sampleAndPublish(
            previewState,
            previewController,
            phaseProjection,
            worldIdentity,
            lifecycleEpoch,
            serverRoundId,
            configRevision,
            objectGroupRevision,
            truncationSignalEnabled,
            false,
            0,
            ChainPreviewBackendDiagnostics.DISABLED);
    }

    /**
     * 扩展采样入口（B5.2）：额外携带执行进度开关与已执行计数。
     *
     * <p>开关与计数都由生产 ticker 从 {@link ChainPreviewExecutionProgress} 采样得到；
     * 未接线的调用方继续走 9 参重载（等价 {@code executionProgressEnabled=false}、{@code executedCount=0}）。</p>
     *
     * @param executionProgressEnabled 执行进度开关（{@code clientPreviewExecutionProgress} 的投影位）
     * @param executedCount 已执行目标数（同代单调不减；开关关闭时应传 0）
     * @param backendDiagnostics 预览后端诊断快照（{@code clientPreviewBackendDiagnostics} 的投影位，
     *                           关闭时传 {@link ChainPreviewBackendDiagnostics#DISABLED}）
     * @return 发布后的当前 header
     */
    public ChainPreviewPresentationHeader sampleAndPublish(
            ChainPreviewState previewState,
            ChainPreviewController previewController,
            ClientPhaseProjection phaseProjection,
            long worldIdentity,
            long lifecycleEpoch,
            long serverRoundId,
            long configRevision,
            long objectGroupRevision,
            boolean truncationSignalEnabled,
            boolean executionProgressEnabled,
            int executedCount,
            ChainPreviewBackendDiagnostics backendDiagnostics) {
        ChainPhase phase = phaseProjection == null ? ChainPhase.IDLE : phaseProjection.getCurrentPhase();
        int serverGeneration = phaseProjection == null ? 0 : phaseProjection.getCurrentGeneration();
        int previewGeneration = previewState == null ? 0 : previewState.getGeneration();
        boolean previewActive = previewState != null && previewState.isActive();
        boolean previewCompleted = previewState != null && previewState.isCompleted();
        int scannedCount = previewState == null ? 0 : previewState.getScannedCount();
        int matchedCount = previewState == null ? 0 : previewState.getMatchedCount();
        ChainPreviewState.TruncationReason truncationReason = previewState == null
                ? ChainPreviewState.TruncationReason.NONE : previewState.getTruncationReason();
        int truncatedCount = previewState == null ? 0 : previewState.getTruncatedCount();
        int totalCount = previewState == null ? 0 : previewState.getTotalCount();
        ChainPreviewState.CancelReason cancelReason = previewState == null
                ? ChainPreviewState.CancelReason.NONE : previewState.getCancelReason();
        boolean remoteRequestPending = previewController != null && previewController.isRemotePreviewPending();
        int remoteRequestId = previewController == null ? 0 : previewController.getRemotePreviewRequestId();
        return project(new ChainPreviewPresentationHeader(
            phase,
            serverGeneration,
            previewGeneration,
            previewActive,
            previewCompleted,
            scannedCount,
            matchedCount,
            matchedCount,
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
            backendDiagnostics,
            0L));
    }

    /** 生命周期清理：丢弃当前 header 与 revision 水位（保留订阅者，下次发布即重新投影）。 */
    public void clear() {
        checkThread("clear");
        currentHeader = null;
        publishedRevision.set(0L);
    }

    /** 生命周期清理：摘除全部订阅者（断线/世界卸载时由装配点调用）。 */
    public void clearSubscriptions() {
        checkThread("clearSubscriptions");
        listeners.clear();
    }

    private void notifyListeners(ChainPreviewPresentationHeader header) {
        for (Listener listener : listeners) {
            try {
                listener.onHeaderChanged(header);
            } catch (RuntimeException failure) {
                MyMod.LOG.warn(
                    "[ChainPreview] Presentation projection listener {} failed",
                    listener.getClass().getName(),
                    failure);
            } catch (LinkageError failure) {
                MyMod.LOG.warn(
                    "[ChainPreview] Presentation projection listener {} incompatible",
                    listener.getClass().getName(),
                    failure);
            }
        }
    }

    private void checkThread(String action) {
        Thread expected = mainThread;
        if (expected != null && Thread.currentThread() != expected) {
            MyMod.LOG.warn(
                "[ChainPreview] Presentation projection {} called from non-main thread {}; expected {}",
                action,
                Thread.currentThread().getName(),
                expected.getName());
        }
    }
}
