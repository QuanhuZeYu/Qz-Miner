package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewAnimationClock;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewBackendReadiness;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewBackendSelector;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewFadeController;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlFences;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewLegacyBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewOverlayPath;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRefreshDecision;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRenderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewVanillaHighlight;
import club.heiqi.qz_miner.chain.client.render.WorldOverlayBackend;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

/**
 * 连锁目标预览渲染器。
 *
 * <p>每帧流程：采样相机 → 选择后端 → 取回 CPU publication → 帧级 GL 状态围栏内上传拓扑 /
 * 颜色并派生 {@link ChainPreviewDrawPlan} → 后端绘制。</p>
 *
 * <p>B3.2 淡入淡出：动画档位为 flow / wave 且 duration &gt; 0 时，出现按 {@link ChainPreviewFadeController}
 * 淡入、预览结束进入 retiring 保留最后一份网格淡出；off 档无过渡、结束立即清空，逐字等于历史行为。
 * 全局乘子经 plan 的 alpha 端点（shader 路径）与 1×1 白纹理 × GL_MODULATE（legacy 路径）施加。</p>
 *
 * <p>B0.4 口径（T48c-A 起更新）：绑定捕获每帧 3 次 glGetInteger（一次捕获、一次恢复），
 * 矩阵模式不再单独查询（glPushAttrib(GL_ALL_ATTRIB_BITS) / glPopAttrib 覆盖）。着色器路径
 * 不再依赖固定管线内建矩阵：每次 draw 经 {@code ChainPreviewShaderProgram#readCameraMatrices}
 * 回读 GL_PROJECTION_MATRIX 与 GL_MODELVIEW_MATRIX 各 1 次 glGetFloat —— XRAY / OCCLUDE 每帧
 * 2 次，OUTLINE 两段 pass 每帧最多 4 次，legacy 路径 0 次。另有每次 draw 1 次
 * GL_CURRENT_PROGRAM 恢复回读、以及按 viewport 变化缓存的 GL_VIEWPORT 回读；这些都不计入
 * 帧级围栏口径，由后端自行计数（T8-D3/D4）。</p>
 *
 * <p>T26 / B4.3：GL 状态恢复、能力探测缓存、线程契约与资源重载令牌统一收敛到
 * {@link WorldOverlayBackend}；renderer 只消费其决策（{@link ChainPreviewOverlayPath}），
 * 并在能力不足（无 VAO / 无 GL20 / 探测失败）时显式降级不绘制 + 一次性诊断。
 * 默认档（xray / legacy / auto）的绘制语义与历史一致；着色器路径的相机矩阵来源自 T48c-A
 * 起改为显式 uniform（见上），并带平移列数值自检 + 一次性永久回退（T48c-B）。</p>
 */
@SideOnly(Side.CLIENT)
public class ChainPreviewRenderer {

    private static final String DEPTH_MODE_OCCLUDE = "occlude";
    private static final String DEPTH_MODE_OUTLINE = "outline";

    private final ChainPreviewRenderCache renderCache;
    private final ChainPreviewScaleCounters scaleCounters = new ChainPreviewScaleCounters();
    private final WorldOverlayBackend overlay;

    private ChainPreviewRenderBackend backend;
    private String lastConfiguredBackendId;
    private boolean shaderAttemptFailed;
    private boolean shaderFallbackReported;
    /** T48c-B：后端首次「就绪使用」只报一次（真机一眼可见跑的是 shader 还是 legacy）。 */
    private boolean backendInUseReported;
    private String backendCreationFailure = "";
    /**
     * 后端诊断快照（渲染线程写、客户端 tick 线程只读）。
     *
     * <p>用 volatile String 引用发布：写入只在状态真正变化时发生（后端首次就绪 / 回退 / 路径不可用），
     * 读取方每 tick 直接取引用，稳态零分配、零锁。这是 HUD 诊断行（Q4）的唯一数据面，
     * 不让 HUD 触碰渲染线程的后端对象。</p>
     */
    private volatile String activeBackendIdSnapshot = "";
    private volatile String backendFallbackReasonSnapshot = "";
    private String lastUnavailableReason = "";
    private ChainPreviewVisualSettings lastVisualSettings;
    private ChainPreviewDrawPlan.Visuals visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
    private final ChainPreviewAnimationClock animationClock = new ChainPreviewAnimationClock();
    private final ChainPreviewFadeController fadeController = new ChainPreviewFadeController();
    private String animationModeId = "";
    private int animationDurationMs;
    /**
     * OUTLINE 档描边壳外扩宽度（物理像素；0 = 关闭描边）。
     *
     * <p>与 {@link #visuals} 同源于 读取面单通道（真源：ChainPreviewVisualSettings）快照（{@code getVisualSettings()} 引用未变时不重复取值），
     * 只在 {@link #drawPreview} 派生壳段计划时消费；XRAY / OCCLUDE 档不读本字段。</p>
     */
    private float outlineWidthPx;

    private int uploadedGeneration = -1;
    private long uploadedStateRevision = -1L;
    private ChainPreviewMesh activeMesh = ChainPreviewMesh.EMPTY;

    public ChainPreviewRenderer(ChainPreviewState previewState) {
        this(ChainPreviewRenderCache.createProduction(previewState));
    }

    ChainPreviewRenderer(ChainPreviewRenderCache renderCache) {
        if (renderCache == null) {
            throw new IllegalArgumentException("renderCache");
        }
        this.renderCache = renderCache;
        this.overlay = new WorldOverlayBackend(scaleCounters);
    }

    /**
     * 注册客户端世界渲染事件。
     */
    public void register() {
        renderCache.observeState();
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在客户端生命周期结束时释放预览后端与 CPU 缓存。
     */
    public void disposeForLifecycle() {
        renderCache.resetForLifecycle();
        disposeBackend();
        overlay.resetForLifecycle();
        lastConfiguredBackendId = null;
        shaderAttemptFailed = false;
        shaderFallbackReported = false;
        backendInUseReported = false;
        backendCreationFailure = "";
        lastUnavailableReason = "";
        activeBackendIdSnapshot = "";
        backendFallbackReasonSnapshot = "";
        lastVisualSettings = null;
        visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
        animationModeId = "";
        animationDurationMs = 0;
        outlineWidthPx = 0.0F;
        animationClock.reset();
        fadeController.reset();
        scaleCounters.reset();
        resetUploadState();
    }

    /**
     * B2.5 原版高亮协同：本帧是否需要抑制原版方块选择框（渲染线程；零分配、无 GL、不写状态）。
     *
     * <p>三条件：视觉快照开关（读取面单通道（真源：ChainPreviewVisualSettings））→ 预览激活（renderCache）→ 瞄准方块与预览代 origin
     * 坐标一致（controller 的 {@link ChainPreviewState#getOrigin()}；B4.1 后 draw plan 的 origin
     * 是代内稳定锚点，不能作为匹配基准）。任一条件不满足 fail-open，原版行为逐字不变；
     * 开关关闭时不再触碰 controller / state（默认档零开销）。</p>
     *
     * @param aimedX 瞄准方块 X
     * @param aimedY 瞄准方块 Y
     * @param aimedZ 瞄准方块 Z
     * @return 是否抑制原版黑色选择框
     */
    public boolean shouldSuppressVanillaHighlight(int aimedX, int aimedY, int aimedZ) {
        ChainPreviewVisualSettings settings = renderCache.getVisualSettings();
        boolean suppress = settings != null && settings.isSuppressVanillaHighlight();
        boolean active = suppress && renderCache.isPreviewActive();
        boolean match = false;
        if (active) {
            ChainPreviewController controller = ClientProxy.chainPreviewController;
            ChainTarget origin = controller == null ? null : controller.getPreviewState().getOrigin();
            if (origin != null) {
                match = ChainPreviewVanillaHighlight.matchesOrigin(
                    true, origin.getX(), origin.getY(), origin.getZ(), aimedX, aimedY, aimedZ);
            }
        }
        return ChainPreviewVanillaHighlight.shouldSuppress(suppress, active, match);
    }

    /**
     * 在世界最后渲染阶段发布已完成的 CPU cache 并按 draw plan 绘制。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null) {
            clearMesh();
            return;
        }
        if (!Config.clientEnablePreviewRender) {
            clearMesh();
            return;
        }
        if (ClientProxy.chainPreviewController == null || MyMod.chainStateService == null) {
            clearMesh();
            return;
        }

        handleOverlayResourceReload();
        refreshVisualSettings();
        boolean previewActive = renderCache.isPreviewActive();
        if (previewActive && fadeController.isRetiring()) {
            // 新预览代抢占：旧 retiring 网格立即丢弃
            clearMesh();
        }
        long nowNanos = System.nanoTime();
        if (previewActive) {
            renderPreviewFrame(nowNanos);
        } else {
            renderRetiringFrame(nowNanos);
        }
    }

    /** 激活帧：采样相机 → 选择后端 → 取回 publication → 帧围栏内上传 / 拟合 plan / 绘制。 */
    private void renderPreviewFrame(long nowNanos) {
        renderCache.refreshForCamera(
            RenderManager.renderPosX,
            RenderManager.renderPosY,
            RenderManager.renderPosZ,
            nowNanos);
        ChainPreviewRenderBackend active = selectBackend();
        if (active == null) {
            clearMesh();
            return;
        }

        ChainPreviewRenderCache.MeshPublication publication = renderCache.pollPublication();
        if (publication == null && activeMesh.isEmpty()) {
            return;
        }

        try (ChainPreviewGlFences.Frame frame = overlay.beginFrame()) {
            active = ensureReadyBackend(active);
            if (active == null) {
                // shader 初始化失败且 legacy 能力不足：本帧显式降级
                return;
            }
            if (publication != null) {
                applyPublication(active, publication);
            }
            float fadeAlpha = fadeController.advance(
                true, uploadedGeneration, fadeEnabled(), animationDurationMs, nowNanos);
            ChainPreviewDrawPlan plan = buildDrawPlan(fadeAlpha);
            if (plan.getIndexCount() > 0) {
                drawPreview(active, plan);
            }
        }
    }

    /**
     * retiring 帧：预览已结束但启用淡入淡出时保留最后一份网格淡出。
     *
     * <p>不 poll publication、不 refreshForCamera、不 selectBackend——retiring 期间不重建拓扑、
     * 不触发后端切换；淡出结束（{@link ChainPreviewFadeController#isIdle()}）即清空索引，
     * GPU 缓冲保留复用、不泄漏。</p>
     */
    private void renderRetiringFrame(long nowNanos) {
        if (backend == null || activeMesh.isEmpty() || !fadeEnabled()) {
            clearMesh();
            return;
        }
        float fadeAlpha = fadeController.advance(
            false, uploadedGeneration, true, animationDurationMs, nowNanos);
        if (fadeController.isIdle()) {
            clearMesh();
            return;
        }
        try (ChainPreviewGlFences.Frame frame = overlay.beginFrame()) {
            ChainPreviewDrawPlan plan = buildDrawPlan(fadeAlpha);
            if (plan.getIndexCount() > 0) {
                drawPreview(backend, plan);
            }
        }
    }

    /**
     * 后端选择（纯函数决策 + 惰性创建；能力探测每 lifecycle 一次，缓存与线程契约见
     * {@link WorldOverlayBackend}）。无 VAO / 无 GL20 / 探测失败时 legacy 不再被假定可用：
     * 决策直接给出不可用，本帧已释放后端并返回 null（调用方清空绘制）。
     */
    private ChainPreviewRenderBackend selectBackend() {
        String configured = configuredBackendId();
        if (lastConfiguredBackendId != null && !lastConfiguredBackendId.equals(configured)) {
            shaderAttemptFailed = false;
            shaderFallbackReported = false;
            backendCreationFailure = "";
            disposeBackend();
        }
        lastConfiguredBackendId = configured;

        ChainPreviewOverlayPath.Decision decision = overlay.planPath(configured, shaderAttemptFailed);
        if (!decision.isUsable()) {
            reportPathUnavailable(decision);
            disposeBackend();
            animationClock.reset();
            fadeController.reset();
            return null;
        }
        lastUnavailableReason = "";

        String selected = decision.getBackendId();
        if (backend != null && selected.equals(backend.id())) {
            return backend;
        }
        disposeBackend();
        animationClock.reset();
        fadeController.reset();
        ChainPreviewRenderBackend created = createBackend(selected);
        if (created == null) {
            reportShaderFallback(selected, configured, "create-failed");
            shaderAttemptFailed = true;
            ChainPreviewOverlayPath.Decision legacy =
                overlay.planPath(ChainPreviewBackendSelector.LEGACY, shaderAttemptFailed);
            if (!legacy.isUsable()) {
                // shader 创建失败且 legacy 能力不足：显式降级，不创建必然失败的后端
                reportPathUnavailable(legacy);
                return null;
            }
            created = createBackend(legacy.getBackendId());
        }
        backend = created;
        return backend;
    }

    /**
     * 后端工厂：legacy 直连创建；shader 走同包静态入口
     * {@link ChainPreviewShaderBackend#create()}，任何加载 / 构造 / 静态初始化异常都在此
     * 收敛为 null，由调用方当帧回退 legacy 并做一次性诊断（不每帧重试）。
     */
    private ChainPreviewRenderBackend createBackend(String id) {
        if (ChainPreviewBackendSelector.LEGACY.equals(id)) {
            // T26：legacy 的纹理乘子 / dispose 绑定围栏共用封装的 GL 状态访问点
            return new ChainPreviewLegacyBackend(overlay.glAccess());
        }
        try {
            return ChainPreviewShaderBackend.create();
        } catch (Throwable failure) {
            backendCreationFailure = failure.getClass().getSimpleName()
                + ": " + String.valueOf(failure.getMessage());
            return null;
        }
    }

    /**
     * 帧内惰性初始化；shader 初始化失败当帧起回退 legacy，不每帧重试。
     *
     * <p>T48c-B：回退动作由纯函数 {@link ChainPreviewBackendReadiness#onNotReady} 决定；
     * legacy 未就绪保持既有行为，shader 未就绪且 legacy 可用 ⇒ 一次性永久回退，
     * 两者都不可用 ⇒ 显式降级不绘制。</p>
     */
    private ChainPreviewRenderBackend ensureReadyBackend(ChainPreviewRenderBackend active) {
        if (active.ensureReady()) {
            reportBackendInUse(active);
            return active;
        }
        ChainPreviewOverlayPath.Decision legacy =
            overlay.planPath(ChainPreviewBackendSelector.LEGACY, true);
        ChainPreviewBackendReadiness.Action action =
            ChainPreviewBackendReadiness.onNotReady(active.id(), legacy.isUsable());
        if (action == ChainPreviewBackendReadiness.Action.KEEP_ACTIVE) {
            return active;
        }
        if (action == ChainPreviewBackendReadiness.Action.NO_USABLE_PATH) {
            reportPathUnavailable(legacy);
            backend = null;
            return null;
        }
        reportShaderFallback(active.id(), lastConfiguredBackendId, "ensureReady-failed");
        shaderAttemptFailed = true;
        overlay.dispose(active);
        animationClock.reset();
        fadeController.reset();
        ChainPreviewRenderBackend fallback = createBackend(legacy.getBackendId());
        backend = fallback;
        return fallback;
    }

    /** 后端首次就绪使用的一次性 INFO 日志（真机可一眼判断实际后端）。 */
    private void reportBackendInUse(ChainPreviewRenderBackend active) {
        if (backendInUseReported) {
            return;
        }
        backendInUseReported = true;
        activeBackendIdSnapshot = active.id() == null ? "" : active.id();
        try {
            MyMod.LOG.info("[ChainPreview] backend in use: id={}, configured={}, caps=[{}]",
                active.id(), lastConfiguredBackendId, overlay.describeCapabilities());
        } catch (Throwable ignored) {
            // 诊断日志异常不得影响渲染帧
        }
    }

    /** @return 当前生效后端 id（空串 = 尚未判定）；跨线程只读快照，供 HUD 诊断行采样 */
    public String describeActiveBackendId() {
        return activeBackendIdSnapshot;
    }

    /** @return 一次性回退原因（空串 = 未发生回退）；跨线程只读快照，供 HUD 诊断行采样 */
    public String describeBackendFallbackReason() {
        return backendFallbackReasonSnapshot;
    }

    /**
     * 拓扑 / 颜色上传分派：拓扑变化走拓扑重传；同 generation + 同 stateRevision 时只有消费
     * CPU 颜色流的后端才走颜色上传——shader 后端颜色由 GPU uniform 计算，同代刷新零上传，
     * 不得退化为整份拓扑重传（B2.3 若需要 CPU 侧颜色，让该后端 usesCpuColors() 返回 true 即可）。
     */
    private void applyPublication(
            ChainPreviewRenderBackend active,
            ChainPreviewRenderCache.MeshPublication publication) {
        // B4.2：把当前代装配会话的容量峰值交接进规模计数器通道（渲染线程、零分配、无 GL）
        renderCache.publishCapacityInto(scaleCounters);
        ChainPreviewMesh mesh = publication.getMesh();
        boolean topologyChanged = mesh.isEmpty()
            || publication.getGeneration() != uploadedGeneration
            || publication.getStateRevision() != uploadedStateRevision;
        ChainPreviewRefreshDecision.Upload upload =
            ChainPreviewRefreshDecision.begin(topologyChanged, active.usesCpuColors());
        if (upload == ChainPreviewRefreshDecision.Upload.COLORS && !active.uploadColors(mesh)) {
            upload = ChainPreviewRefreshDecision.fallback(upload);
        }
        if (upload == ChainPreviewRefreshDecision.Upload.TOPOLOGY) {
            active.uploadTopology(mesh);
            // B2.4：剔除计数随拓扑上传累计（同代颜色刷新不重计）；lod=off 时 mesh 恒报 0
            scaleCounters.recordCulled(mesh.getCulledTargetCount());
        }
        scaleCounters.record(upload, !mesh.isEmpty());
        uploadedGeneration = publication.getGeneration();
        uploadedStateRevision = publication.getStateRevision();
        activeMesh = mesh;
    }

    /**
     * 派生本帧 draw plan。
     *
     * <p>{@code animationU} 由 {@link ChainPreviewAnimationClock} 逐帧产出（off 恒 1、
     * flow / wave 按 duration 线性、掉帧钳制到 1）；{@code fadeAlpha} 由
     * {@link ChainPreviewFadeController} 产出并按等价缩放注入 α 端点（shader 路径生效）、
     * 同时记录给 legacy 做纹理乘子。{@code waveEnds} 恒为 null：索引顺序 != appearOrder 顺序，
     * 索引段无法表达逐波，本轮不生成索引段波表；逐波生长由 shader 按 aAux 逐顶点 appearOrder
     * 比较实现（T11），legacy 路径整体绘制。</p>
     */
    private ChainPreviewDrawPlan buildDrawPlan(float fadeAlpha) {
        ChainPreviewMesh mesh = activeMesh == null ? ChainPreviewMesh.EMPTY : activeMesh;
        return ChainPreviewDrawPlan.derive(
            mesh,
            0,
            mesh.getIndexCount(),
            null,
            currentVisuals(fadeAlpha),
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            mesh.getOriginX(),
            mesh.getOriginY(),
            mesh.getOriginZ(),
            scaleCounters.getRebuilds(),
            scaleCounters.getUploads());
    }

    /** 视觉参数 / 动画档位快照刷新：settings 引用未变时零分配复用。 */
    private void refreshVisualSettings() {
        ChainPreviewVisualSettings settings = renderCache.getVisualSettings();
        if (settings == lastVisualSettings) {
            return;
        }
        lastVisualSettings = settings;
        if (settings == null) {
            visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
            animationModeId = "";
            animationDurationMs = 0;
            outlineWidthPx = 0.0F;
            return;
        }
        visuals = visualsFromSettings(settings);
        animationModeId = settings.getAnimationId();
        animationDurationMs = settings.getAnimationDurationMs();
        outlineWidthPx = settings.getOutlineWidthPx();
    }

    /** @return 是否启用淡入 / 淡出（animation ∈ {flow, wave} 且 duration &gt; 0） */
    private boolean fadeEnabled() {
        return ChainPreviewFadeController.isFadeEnabled(animationModeId, animationDurationMs);
    }

    /**
     * 视觉参数 + 动画完成度 + 全局淡入淡出乘子；三者未变化时对应 with* 方法返回自身（零分配）。
     */
    private ChainPreviewDrawPlan.Visuals currentVisuals(float fadeAlpha) {
        float animationU = animationClock.advance(
            uploadedGeneration,
            animationModeId,
            animationDurationMs,
            System.nanoTime());
        return visuals.withAnimationU(animationU).withFadeAlpha(fadeAlpha);
    }

    private static ChainPreviewDrawPlan.Visuals visualsFromSettings(ChainPreviewVisualSettings settings) {
        return new ChainPreviewDrawPlan.Visuals(
            settings.getBarThickness(),
            settings.getMinScreenWidthPx(),
            ChainPreviewDrawPlan.ANIMATION_COMPLETE,
            settings.getAlphaFadeStartRadius(),
            settings.getAlphaFadeEndRadius(),
            settings.getAlphaStartValue(),
            settings.getAlphaEndValue(),
            mapDepthChannel(settings.getDepthModeId()),
            1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
                settings.getColorSourceId(),
                settings.getColorChain(),
                settings.getColorArea(),
                settings.getColorInteract(),
                settings.getColorSecondary(),
                settings.getColorRemote(),
                settings.getColorTruncated()),
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig(
                settings.getLodId(),
                settings.getLodMinAlpha()),
            false,
            0.0F,
            settings.isFaceShadingEnabled(),
            settings.getOrderMinBrightness(),
            settings.getInteriorDim());
    }

    private static ChainPreviewDrawPlan.DepthChannel mapDepthChannel(String depthModeId) {
        if (DEPTH_MODE_OCCLUDE.equals(depthModeId)) {
            return ChainPreviewDrawPlan.DepthChannel.OCCLUDE;
        }
        if (DEPTH_MODE_OUTLINE.equals(depthModeId)) {
            return ChainPreviewDrawPlan.DepthChannel.OUTLINE;
        }
        return ChainPreviewDrawPlan.DepthChannel.XRAY;
    }

    /** 后端档位经 读取面单通道（真源：ChainPreviewVisualSettings）（ChainPreviewVisualSettings）获取，renderer 不再直连 Config（T8-D10）。 */
    private String configuredBackendId() {
        ChainPreviewVisualSettings settings = renderCache.getVisualSettings();
        return settings == null ? ChainPreviewBackendSelector.AUTO : settings.getRenderBackendId();
    }

    /** 执行绘制；状态与矩阵由帧级围栏恢复。 */
    private void drawPreview(ChainPreviewRenderBackend active, ChainPreviewDrawPlan plan) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                plan.getOriginX() - RenderManager.renderPosX,
                plan.getOriginY() - RenderManager.renderPosY,
                plan.getOriginZ() - RenderManager.renderPosZ);
            // 描边宽度参与档位解析：宽度 0 时 OUTLINE 收敛为单遍 XRAY（推导见
            // ChainPreviewDepthPass.resolvePass）——否则壳段与主体几何相同，stage 仍按
            // 2 段执行，同一处被混合两次（alpha 0.78 → 0.9516）。
            ChainPreviewDepthPass.Pass pass = ChainPreviewDepthPass.resolvePass(
                plan.getDepthChannel(),
                outlineWidthPx);
            int stageCount = ChainPreviewDepthPass.stageCount(pass);
            // B3.x 真描边：仅 OUTLINE 档派生一次壳段计划（XRAY / OCCLUDE 零分配、逐字节等于现状）；
            // 外扩宽度取 读取面单通道（真源：ChainPreviewVisualSettings）（配置 clientPreviewOutlineWidthPx），进到这里必然 > 0。
            ChainPreviewDrawPlan shellPlan = pass == ChainPreviewDepthPass.Pass.OUTLINE
                ? plan.withOutlinePass(true, outlineWidthPx)
                : null;
            for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
                applyDepthStage(ChainPreviewDepthPass.stage(pass, stageIndex));
                active.draw(
                    ChainPreviewDepthPass.isOutlineShellStage(pass, stageIndex) ? shellPlan : plan);
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    /**
     * 施加一档深度状态。XRAY 档的调用序列与历史完全一致（关深度测试 + depthMask(false)，
     * 不设置 depthFunc）；深度分层只重选 pass，不改拓扑、不重建（T15）。
     */
    private static void applyDepthStage(ChainPreviewDepthPass.Stage stage) {
        if (stage.isDepthTestEnabled()) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(stage.getDepthFunc());
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        GL11.glDepthMask(stage.isDepthMaskEnabled());
    }

    /**
     * 清理当前缓存的预览网格（空网格上传 = 清空索引）。
     *
     * <p>该路径可能在帧围栏之外被调用（预览未激活 / 世界为空 / 配置或生命周期早退），
     * 因此自带绑定围栏（T8-D2b）；空网格上传按接口契约不得触碰 GL，围栏是防御后端违规的加固。</p>
     */
    private void clearMesh() {
        animationClock.reset();
        fadeController.reset();
        ChainPreviewRenderBackend active = backend;
        if (active == null) {
            resetUploadState();
            return;
        }
        overlay.clearMesh(active);
        resetUploadState();
    }

    private void resetUploadState() {
        uploadedGeneration = -1;
        uploadedStateRevision = -1L;
        activeMesh = ChainPreviewMesh.EMPTY;
    }

    private void disposeBackend() {
        if (backend != null) {
            overlay.dispose(backend);
            backend = null;
        }
    }

    /**
     * 帧内消费资源重载信号：只作废后端 / 上传状态 / 失败记忆，真正的重建推迟到下一次后端选择
     * （惰性，T26）——不跨代、不跨世界保留 GL 对象，也不在资源重载事件里做任何 GL 操作。
     */
    private void handleOverlayResourceReload() {
        if (!overlay.consumeResourceReload()) {
            return;
        }
        disposeBackend();
        animationClock.reset();
        fadeController.reset();
        resetUploadState();
        lastConfiguredBackendId = null;
        shaderAttemptFailed = false;
        shaderFallbackReported = false;
        backendInUseReported = false;
        backendCreationFailure = "";
        lastUnavailableReason = "";
        MyMod.LOG.debug("[ChainPreview] overlay cache invalidated ({}), rebuild deferred to next frame",
            overlay.getLastResourceReason());
    }

    /**
     * 纹理图集重载（F3+T / 资源包切换）即资源重载信号：只作废缓存，不做 GL 操作（T26）。
     *
     * @param event 纹理缝合事件
     */
    @SubscribeEvent
    public void onTextureStitch(TextureStitchEvent.Post event) {
        overlay.markResourcesDirty("textureStitch");
    }

    /** 显式降级诊断：原因变化时一次性告警（无 VAO / 无 GL20 / 能力探测失败 / 无可用路径）。 */
    private void reportPathUnavailable(ChainPreviewOverlayPath.Decision decision) {
        String reason = decision == null ? "unknown" : decision.getReason();
        if (reason.equals(lastUnavailableReason)) {
            return;
        }
        lastUnavailableReason = reason;
        backendFallbackReasonSnapshot = reason;
        MyMod.LOG.warn("[ChainPreview] preview overlay unavailable: " + reason + "; " + overlay.describe());
    }

    /**
     * 一次性回退 WARN（T48c-B：含原因串，真机一眼可见「是否回退 + 为什么」）。
     *
     * @param selectedId 尝试使用但不可用的后端 id
     * @param configured 当前配置档位
     * @param reason     回退原因（create-failed / ensureReady-failed）
     */
    private void reportShaderFallback(String selectedId, String configured, String reason) {
        if (shaderFallbackReported) {
            return;
        }
        shaderFallbackReported = true;
        // 回退后允许下一帧再报一次「backend in use: legacy」
        backendInUseReported = false;
        // 诊断快照：回退目标恒为 legacy；原因取本次回退分类（HUD 诊断行读它）。
        activeBackendIdSnapshot = ChainPreviewBackendSelector.LEGACY;
        backendFallbackReasonSnapshot = reason == null ? "" : reason;
        MyMod.LOG.warn("[ChainPreview] shader backend unavailable, fallback to legacy"
            + " (configured=" + configured
            + ", selected=" + selectedId
            + ", reason=" + reason
            + (backendCreationFailure.isEmpty() ? "" : ", creationFailure=" + backendCreationFailure)
            + ", caps=" + overlay.describeCapabilities() + ")");
    }
}
