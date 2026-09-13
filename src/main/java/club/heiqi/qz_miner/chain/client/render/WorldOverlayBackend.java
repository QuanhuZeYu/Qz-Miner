package club.heiqi.qz_miner.chain.client.render;

import java.util.Objects;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 世界覆盖层后端封装（T26 / B4.3）：预览渲染「GL 状态恢复 + 能力探测 + 线程契约 + 资源重载」的
 * 唯一持有者，renderer 与后端不再各自维护这些假设。
 *
 * <p>职责边界：</p>
 *
 * <ul>
 *   <li><b>GL 状态恢复</b>：帧级围栏 {@link #beginFrame()}、帧外清空 {@link #clearMesh}、
 *       通用帧外围栏 {@link #runFenced} 全部委托 {@link ChainPreviewGlFences}，恢复动作只有一份实现；
 *       后端通过 {@link #glAccess()} 拿到同一个 GL 状态访问点（legacy 纹理乘子围栏共用）；</li>
 *   <li><b>能力探测</b>：{@link #capabilities()} 每 lifecycle 只探测一次并缓存；探测异常 / 空结果
 *       显式记录（{@link #describe()} 可见），资源重载只作废缓存、下一帧按需重探（惰性）；</li>
 *   <li><b>路径决策接线</b>：{@link #planPath} 委托纯函数 {@link ChainPreviewOverlayPath#decide}
 *       （无 GL），并累计显式降级事件；</li>
 *   <li><b>线程契约</b>：GPU 释放在渲染线程（{@link #dispose}），校验只在 debug 档开启
 *       （{@link #setThreadContractEnforced} / {@link #THREAD_CONTRACT_PROPERTY}），默认零校验开销；</li>
 *   <li><b>资源重载</b>：{@link #markResourcesDirty} 只置位，{@link #consumeResourceReload} 由
 *       渲染帧消费，具体重建交给 renderer 在下一帧惰性执行，不跨代 / 跨世界保留 GL 对象。</li>
 * </ul>
 *
 * <p>本类不创建后端、不绘制：后端选择保持 {@link ChainPreviewBackendSelector} 语义，由 renderer 完成，
 * 以保证「配置热切换 → 下一帧生效、旧后端渲染线程 dispose」的历史行为逐字不变。</p>
 */
public final class WorldOverlayBackend {

    /** 能力探测 seam（默认 {@link ChainPreviewGlCapabilities#detectDetailed()}）。 */
    public interface CapabilityProbe {

        /** @return 探测结果，可为 null（视为探测失败） */
        ChainPreviewGlCapabilities.ProbeResult probe();
    }

    /** 线程契约校验系统属性：{@code -Dqz_miner.preview.debugThreadContract=true} 时强制开启。 */
    public static final String THREAD_CONTRACT_PROPERTY = "qz_miner.preview.debugThreadContract";

    private final ChainPreviewScaleCounters scaleCounters;
    private final ChainPreviewGlFences.Access glAccess;
    private final CapabilityProbe capabilityProbe;

    private ChainPreviewGlCapabilities capabilities;
    private boolean capabilitiesDirty = true;
    private String lastProbeFailure = "";
    private long probeCount;
    private long probeFailures;

    private long frames;
    private long frameOpenFailures;
    private String lastFrameFailure = "";
    private long disposals;
    private long disposalFailures;
    private String lastDisposalFailure = "";

    private long degradationEvents;
    private String lastDegradationReason = "";
    private String currentDegradationReason = "";
    private ChainPreviewOverlayPath.Decision cachedDecision;
    private ChainPreviewGlCapabilities cachedDecisionCapabilities;
    private String cachedDecisionConfigured;
    private boolean cachedDecisionAttemptFailed;

    private boolean resourceDirty;
    private long resourceReloads;
    private String lastResourceReason = "";

    private long contractViolations;
    private String lastContractViolation = "";
    private Thread renderThread;
    private Boolean threadContractEnforced;

    public WorldOverlayBackend(ChainPreviewScaleCounters scaleCounters) {
        this(scaleCounters, ChainPreviewGlFences.LWJGL, ChainPreviewGlCapabilities::detectDetailed);
    }

    /**
     * 注入版构造：离线 / 探针测试用。
     *
     * @param scaleCounters 帧级捕获计数（可为 null，内部兜底一个实例）
     * @param glAccess      GL 状态访问点（可为 null → LWJGL）
     * @param capabilityProbe 能力探测点（可为 null → 真实 GL 探测）
     */
    public WorldOverlayBackend(
            ChainPreviewScaleCounters scaleCounters,
            ChainPreviewGlFences.Access glAccess,
            CapabilityProbe capabilityProbe) {
        this.scaleCounters = scaleCounters == null ? new ChainPreviewScaleCounters() : scaleCounters;
        this.glAccess = glAccess == null ? ChainPreviewGlFences.LWJGL : glAccess;
        this.capabilityProbe = capabilityProbe == null
            ? ChainPreviewGlCapabilities::detectDetailed
            : capabilityProbe;
    }

    /** @return 帧级捕获计数对象（与 draw plan 同源） */
    public ChainPreviewScaleCounters getScaleCounters() {
        return scaleCounters;
    }

    /** @return 统一 GL 状态访问点（legacy 后端纹理乘子围栏共用） */
    public ChainPreviewGlFences.Access glAccess() {
        return glAccess;
    }

    /**
     * 能力探测 + 缓存（唯一入口）。
     *
     * <p>惰性：首次调用探测一次，之后复用缓存；{@link #markResourcesDirty} 只置脏位，
     * 下一次调用才重新探测。探测抛异常 / 返回 null 都收敛为 {@link ChainPreviewGlCapabilities#UNSUPPORTED}
     * 并记录失败原因，不抛给渲染帧。</p>
     *
     * @return 能力结果（永不为 null）
     */
    public ChainPreviewGlCapabilities capabilities() {
        if (capabilities == null || capabilitiesDirty) {
            refreshCapabilities();
        }
        return capabilities;
    }

    private void refreshCapabilities() {
        capabilitiesDirty = false;
        probeCount++;
        try {
            ChainPreviewGlCapabilities.ProbeResult result =
                capabilityProbe == null ? null : capabilityProbe.probe();
            if (result == null) {
                capabilities = ChainPreviewGlCapabilities.UNSUPPORTED;
                noteProbeFailure("capability probe returned null");
                return;
            }
            ChainPreviewGlCapabilities probed = result.getCapabilities();
            capabilities = probed == null ? ChainPreviewGlCapabilities.UNSUPPORTED : probed;
            if (result.isFailure()) {
                noteProbeFailure(result.getFailureReason());
            } else {
                lastProbeFailure = "";
            }
        } catch (Throwable failure) {
            capabilities = ChainPreviewGlCapabilities.UNSUPPORTED;
            noteProbeFailure(describe(failure));
        }
    }

    private void noteProbeFailure(String reason) {
        probeFailures++;
        lastProbeFailure = reason == null || reason.isEmpty() ? "unspecified" : reason;
    }

    /** @return 已缓存能力的诊断文本；尚未探测时为 {@code unprobed} */
    public String describeCapabilities() {
        ChainPreviewGlCapabilities current = capabilities;
        return current == null ? "unprobed" : current.describe();
    }

    /**
     * 纯决策接线：能力 + 配置 + 上次失败 → 路径决策；不可用时累计一次显式降级事件
     * （原因变化才计数，避免每帧噪声）。
     *
     * @param configured          后端档位（auto / shader / legacy）
     * @param shaderAttemptFailed 上次 shader 后端加载 / 初始化是否失败
     * @return 决策结果（永不为 null）
     */
    public ChainPreviewOverlayPath.Decision planPath(String configured, boolean shaderAttemptFailed) {
        ChainPreviewGlCapabilities caps = capabilities();
        ChainPreviewOverlayPath.Decision decision = cachedDecision;
        if (decision == null
                || cachedDecisionCapabilities != caps
                || cachedDecisionAttemptFailed != shaderAttemptFailed
                || !Objects.equals(cachedDecisionConfigured, configured)) {
            // 输入未变时复用同一决策实例：每帧零分配、零字符串拼接
            decision = ChainPreviewOverlayPath.decide(configured, caps, shaderAttemptFailed);
            cachedDecision = decision;
            cachedDecisionCapabilities = caps;
            cachedDecisionConfigured = configured;
            cachedDecisionAttemptFailed = shaderAttemptFailed;
            noteDecision(decision);
        }
        return decision;
    }

    private void noteDecision(ChainPreviewOverlayPath.Decision decision) {
        if (decision.isUsable()) {
            currentDegradationReason = "";
            return;
        }
        currentDegradationReason = decision.getReason();
        if (!currentDegradationReason.equals(lastDegradationReason)) {
            lastDegradationReason = currentDegradationReason;
            degradationEvents++;
        }
    }

    /**
     * 打开帧级围栏：捕获绑定快照（计入帧级捕获计数）+ 压入状态栈；
     * 与 {@link ChainPreviewGlFences.Frame#close()} 严格配对。
     *
     * @return 帧围栏
     */
    public ChainPreviewGlFences.Frame beginFrame() {
        if (renderThread == null) {
            renderThread = Thread.currentThread();
        } else {
            assertRenderThread("beginFrame");
        }
        frames++;
        ChainPreviewGlFences.Frame frame =
            ChainPreviewGlFences.Frame.open(glAccess, scaleCounters::recordBindingCapture);
        String frameFailure = frame.getFailure();
        if (!frameFailure.isEmpty()) {
            frameOpenFailures++;
            lastFrameFailure = frameFailure;
        }
        return frame;
    }

    /**
     * 帧外清空拓扑：自带静默绑定围栏（空网格按契约不触碰 GL，围栏是防御后端违规的加固）。
     *
     * @param target 目标后端，可为 null（无后端时无操作）
     */
    public void clearMesh(ChainPreviewRenderBackend target) {
        if (target == null) {
            return;
        }
        runFenced("clearMesh", () -> target.uploadTopology(ChainPreviewMesh.EMPTY));
    }

    /**
     * 通用帧外静默围栏：捕获绑定快照（不计入帧级捕获计数，与历史 captureQuietly 口径一致）→
     * 执行 body → finally 恢复；捕获 / 恢复失败静默降级，body 异常照常上抛。
     *
     * @param operation 诊断用操作名
     * @param body      围栏内操作
     */
    public void runFenced(String operation, Runnable body) {
        if (body == null) {
            return;
        }
        assertRenderThread(operation);
        // 状态访问点解析也纳入静默路径：上下文失效不得从帧外入口逃逸
        ChainPreviewGlBindings.Access bindingAccess = ChainPreviewGlFences.bindingsQuietly(glAccess);
        ChainPreviewGlBindings bindings = ChainPreviewGlFences.captureBindingsQuietly(bindingAccess);
        try {
            body.run();
        } finally {
            ChainPreviewGlFences.restoreBindingsQuietly(bindings, bindingAccess);
        }
    }

    /**
     * GPU 释放入口：线程契约校验（debug 档）+ 释放异常隔离（不得逃逸渲染帧）。
     *
     * @param target 待释放后端，可为 null
     */
    public void dispose(ChainPreviewRenderBackend target) {
        if (target == null) {
            return;
        }
        assertRenderThread("dispose");
        disposals++;
        try {
            target.dispose();
        } catch (Throwable failure) {
            disposalFailures++;
            lastDisposalFailure = describe(failure);
        }
    }

    /**
     * 资源重载 / 上下文变化信号：只置脏位（能力缓存 + 资源令牌），不做任何 GL 操作、
     * 不重建后端；重建由渲染帧消费 {@link #consumeResourceReload()} 后惰性执行。
     *
     * @param reason 诊断用来源（可为 null）
     */
    public void markResourcesDirty(String reason) {
        resourceDirty = true;
        capabilitiesDirty = true;
        lastResourceReason = reason == null || reason.trim().isEmpty() ? "unspecified" : reason;
        resourceReloads++;
    }

    /** @return 是否有待消费的资源重载信号（消费一次后返回 false） */
    public boolean consumeResourceReload() {
        if (!resourceDirty) {
            return false;
        }
        resourceDirty = false;
        return true;
    }

    /** @return 是否处于资源重载待消费状态 */
    public boolean isResourceDirty() {
        return resourceDirty;
    }

    /**
     * 线程契约纯判定：返回违反说明，合规返回空串。
     *
     * @param enforced       是否开启校验（debug 档）
     * @param onRenderThread 当前是否在渲染线程
     * @param operation      操作名
     * @return 违反说明或空串
     */
    public static String threadContractViolation(
            boolean enforced, boolean onRenderThread, String operation) {
        if (!enforced || onRenderThread) {
            return "";
        }
        String name = operation == null || operation.isEmpty() ? "operation" : operation;
        return "render-thread contract violated: " + name + " executed off the render thread";
    }

    private void assertRenderThread(String operation) {
        if (!isThreadContractEnforced()) {
            return;
        }
        Thread owner = renderThread;
        if (owner == null || owner == Thread.currentThread()) {
            return;
        }
        String violation = threadContractViolation(true, false, operation);
        contractViolations++;
        if (!violation.equals(lastContractViolation)) {
            lastContractViolation = violation;
            try {
                MyMod.LOG.warn("[ChainPreview] " + violation + "; " + describe());
            } catch (Throwable ignored) {
                // 诊断日志异常不得影响渲染帧
            }
        } else {
            lastContractViolation = violation;
        }
    }

    /**
     * 线程契约校验开关。
     *
     * @param enforced true / false 强制开关；null = 跟随 debug 日志级别与系统属性
     */
    public void setThreadContractEnforced(Boolean enforced) {
        this.threadContractEnforced = enforced;
    }

    /** @return 当前是否开启线程契约校验（默认：debug 日志级别或系统属性开启） */
    public boolean isThreadContractEnforced() {
        Boolean override = threadContractEnforced;
        if (override != null) {
            return override;
        }
        try {
            return MyMod.LOG.isDebugEnabled() || Boolean.getBoolean(THREAD_CONTRACT_PROPERTY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 生命周期清理：能力缓存作废（下一帧重探）、降级 / 重载 / 契约诊断归零、
     * 渲染线程归属清除；资源释放由调用方经 {@link #dispose} 完成。
     */
    public void resetForLifecycle() {
        capabilities = null;
        capabilitiesDirty = true;
        lastProbeFailure = "";
        probeCount = 0L;
        probeFailures = 0L;
        frames = 0L;
        frameOpenFailures = 0L;
        lastFrameFailure = "";
        disposals = 0L;
        disposalFailures = 0L;
        lastDisposalFailure = "";
        degradationEvents = 0L;
        lastDegradationReason = "";
        currentDegradationReason = "";
        cachedDecision = null;
        cachedDecisionCapabilities = null;
        cachedDecisionConfigured = null;
        cachedDecisionAttemptFailed = false;
        resourceDirty = false;
        resourceReloads = 0L;
        lastResourceReason = "";
        contractViolations = 0L;
        lastContractViolation = "";
        renderThread = null;
    }

    public long getFrames() {
        return frames;
    }

    public long getFrameOpenFailures() {
        return frameOpenFailures;
    }

    /** @return 最近一次帧围栏打开失败原因，无失败为空串 */
    public String getLastFrameFailure() {
        return lastFrameFailure;
    }

    public long getProbeCount() {
        return probeCount;
    }

    public long getProbeFailures() {
        return probeFailures;
    }

    public String getLastProbeFailure() {
        return lastProbeFailure;
    }

    public long getDisposals() {
        return disposals;
    }

    public long getDisposalFailures() {
        return disposalFailures;
    }

    public String getLastDisposalFailure() {
        return lastDisposalFailure;
    }

    public long getDegradationEvents() {
        return degradationEvents;
    }

    /** @return 本帧决策的降级原因；可用时为空串 */
    public String getCurrentDegradationReason() {
        return currentDegradationReason;
    }

    public long getResourceReloads() {
        return resourceReloads;
    }

    public String getLastResourceReason() {
        return lastResourceReason;
    }

    public long getContractViolations() {
        return contractViolations;
    }

    public String getLastContractViolation() {
        return lastContractViolation;
    }

    /** @return 诊断文本（能力缓存、探测失败、围栏帧数、释放、降级、资源重载、线程契约） */
    public String describe() {
        StringBuilder text = new StringBuilder("overlay{caps=").append(describeCapabilities())
            .append(", probes=").append(probeCount)
            .append(", probeFailures=").append(probeFailures);
        if (!lastProbeFailure.isEmpty()) {
            text.append(", probeFailure='").append(lastProbeFailure).append('\'');
        }
        text.append(", frames=").append(frames)
            .append(", frameOpenFailures=").append(frameOpenFailures);
        if (!lastFrameFailure.isEmpty()) {
            text.append(", frameFailure='").append(lastFrameFailure).append('\'');
        }
        text.append(", disposals=").append(disposals)
            .append(", disposalFailures=").append(disposalFailures);
        if (!lastDisposalFailure.isEmpty()) {
            text.append(", disposalFailure='").append(lastDisposalFailure).append('\'');
        }
        text.append(", degradations=").append(degradationEvents);
        if (!currentDegradationReason.isEmpty()) {
            text.append(", degradation='").append(currentDegradationReason).append('\'');
        }
        text.append(", resourceReloads=").append(resourceReloads)
            .append(", resourceDirty=").append(resourceDirty);
        if (!lastResourceReason.isEmpty()) {
            text.append(", lastResource='").append(lastResourceReason).append('\'');
        }
        text.append(", contractViolations=").append(contractViolations);
        if (!lastContractViolation.isEmpty()) {
            text.append(", contractViolation='").append(lastContractViolation).append('\'');
        }
        text.append('}');
        return text.toString();
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }
}
