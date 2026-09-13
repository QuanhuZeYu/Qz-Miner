package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewAnimationClock;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewBackendSelector;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewFadeController;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlBindings;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlCapabilities;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewLegacyBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRefreshDecision;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRenderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderBackend;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;
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
 * <p>B0.4 口径：绑定捕获每帧 3 次 glGetInteger（一次捕获、一次恢复），矩阵模式不再单独查询
 * （glPushAttrib(GL_ALL_ATTRIB_BITS) / glPopAttrib 覆盖）；着色器路径另有每帧 1 次 GL_VIEWPORT
 * 与 1 次 program 恢复回读，GL_PROJECTION_MATRIX 仅在 viewport 变化时读，这些不计入本口径，
 * 由 T6 后端自行计数（T8-D3/D4）。</p>
 */
@SideOnly(Side.CLIENT)
public class ChainPreviewRenderer {

    private static final String DEPTH_MODE_OCCLUDE = "occlude";
    private static final String DEPTH_MODE_OUTLINE = "outline";

    private final ChainPreviewRenderCache renderCache;
    private final ChainPreviewScaleCounters scaleCounters = new ChainPreviewScaleCounters();

    private ChainPreviewRenderBackend backend;
    private ChainPreviewGlCapabilities capabilities;
    private String lastConfiguredBackendId;
    private boolean shaderAttemptFailed;
    private boolean shaderFallbackReported;
    private String backendCreationFailure = "";
    private ChainPreviewVisualSettings lastVisualSettings;
    private ChainPreviewDrawPlan.Visuals visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
    private final ChainPreviewAnimationClock animationClock = new ChainPreviewAnimationClock();
    private final ChainPreviewFadeController fadeController = new ChainPreviewFadeController();
    private String animationModeId = "";
    private int animationDurationMs;

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
        capabilities = null;
        lastConfiguredBackendId = null;
        shaderAttemptFailed = false;
        shaderFallbackReported = false;
        backendCreationFailure = "";
        lastVisualSettings = null;
        visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
        animationModeId = "";
        animationDurationMs = 0;
        animationClock.reset();
        fadeController.reset();
        scaleCounters.reset();
        resetUploadState();
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

        ChainPreviewGlBindings bindings = ChainPreviewGlBindings.capture();
        scaleCounters.recordBindingCapture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            try {
                active = ensureReadyBackend(active);
                if (publication != null) {
                    applyPublication(active, publication);
                }
                float fadeAlpha = fadeController.advance(
                    true, uploadedGeneration, fadeEnabled(), animationDurationMs, nowNanos);
                ChainPreviewDrawPlan plan = buildDrawPlan(fadeAlpha);
                if (plan.getIndexCount() > 0) {
                    drawPreview(active, plan);
                }
            } finally {
                GL11.glPopClientAttrib();
            }
        } finally {
            GL11.glPopAttrib();
            bindings.restore();
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
        ChainPreviewGlBindings bindings = ChainPreviewGlBindings.capture();
        scaleCounters.recordBindingCapture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            try {
                ChainPreviewDrawPlan plan = buildDrawPlan(fadeAlpha);
                if (plan.getIndexCount() > 0) {
                    drawPreview(backend, plan);
                }
            } finally {
                GL11.glPopClientAttrib();
            }
        } finally {
            GL11.glPopAttrib();
            bindings.restore();
        }
    }

    /** 后端选择（纯函数决策 + 惰性创建；能力探测每 lifecycle 一次）。 */
    private ChainPreviewRenderBackend selectBackend() {
        if (capabilities == null) {
            capabilities = ChainPreviewGlCapabilities.detect();
        }
        String configured = configuredBackendId();
        if (lastConfiguredBackendId != null && !lastConfiguredBackendId.equals(configured)) {
            shaderAttemptFailed = false;
            shaderFallbackReported = false;
            backendCreationFailure = "";
            disposeBackend();
        }
        lastConfiguredBackendId = configured;

        String selected = ChainPreviewBackendSelector.select(configured, capabilities, shaderAttemptFailed);
        if (backend != null && selected.equals(backend.id())) {
            return backend;
        }
        disposeBackend();
        animationClock.reset();
        fadeController.reset();
        ChainPreviewRenderBackend created = createBackend(selected);
        if (created == null) {
            reportShaderFallback(selected, configured);
            shaderAttemptFailed = true;
            created = createBackend(ChainPreviewBackendSelector.LEGACY);
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
            return new ChainPreviewLegacyBackend();
        }
        try {
            return ChainPreviewShaderBackend.create();
        } catch (Throwable failure) {
            backendCreationFailure = failure.getClass().getSimpleName()
                + ": " + String.valueOf(failure.getMessage());
            return null;
        }
    }

    /** 帧内惰性初始化；shader 初始化失败当帧起回退 legacy，不每帧重试。 */
    private ChainPreviewRenderBackend ensureReadyBackend(ChainPreviewRenderBackend active) {
        if (active.ensureReady()) {
            return active;
        }
        if (ChainPreviewBackendSelector.LEGACY.equals(active.id())) {
            return active;
        }
        reportShaderFallback(active.id(), lastConfiguredBackendId);
        shaderAttemptFailed = true;
        active.dispose();
        animationClock.reset();
        fadeController.reset();
        ChainPreviewRenderBackend fallback = createBackend(ChainPreviewBackendSelector.LEGACY);
        backend = fallback;
        return fallback;
    }

    /**
     * 拓扑 / 颜色上传分派：拓扑变化走拓扑重传；同 generation + 同 stateRevision 时只有消费
     * CPU 颜色流的后端才走颜色上传——shader 后端颜色由 GPU uniform 计算，同代刷新零上传，
     * 不得退化为整份拓扑重传（B2.3 若需要 CPU 侧颜色，让该后端 usesCpuColors() 返回 true 即可）。
     */
    private void applyPublication(
            ChainPreviewRenderBackend active,
            ChainPreviewRenderCache.MeshPublication publication) {
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
            return;
        }
        visuals = visualsFromSettings(settings);
        animationModeId = settings.getAnimationId();
        animationDurationMs = settings.getAnimationDurationMs();
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
                settings.getColorPrimary(),
                settings.getColorSecondary(),
                settings.getColorRemote(),
                settings.getColorTruncated()));
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

    /** 后端档位经 §H 读取面（ChainPreviewVisualSettings）获取，renderer 不再直连 Config（T8-D10）。 */
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
            ChainPreviewDepthPass.Pass pass = ChainPreviewDepthPass.select(plan.getDepthChannel());
            int stageCount = ChainPreviewDepthPass.stageCount(pass);
            for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
                applyDepthStage(ChainPreviewDepthPass.stage(pass, stageIndex));
                active.draw(plan);
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
        if (backend == null) {
            resetUploadState();
            return;
        }
        ChainPreviewGlBindings bindings = captureQuietly();
        try {
            backend.uploadTopology(ChainPreviewMesh.EMPTY);
        } finally {
            restoreQuietly(bindings);
        }
        resetUploadState();
    }

    private static ChainPreviewGlBindings captureQuietly() {
        try {
            return ChainPreviewGlBindings.capture();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void restoreQuietly(ChainPreviewGlBindings bindings) {
        if (bindings == null) {
            return;
        }
        try {
            bindings.restore();
        } catch (Throwable ignored) {
            // 上下文失效时围栏恢复失败不得逃逸渲染帧
        }
    }

    private void resetUploadState() {
        uploadedGeneration = -1;
        uploadedStateRevision = -1L;
        activeMesh = ChainPreviewMesh.EMPTY;
    }

    private void disposeBackend() {
        if (backend != null) {
            backend.dispose();
            backend = null;
        }
    }

    private void reportShaderFallback(String selectedId, String configured) {
        if (shaderFallbackReported) {
            return;
        }
        shaderFallbackReported = true;
        MyMod.LOG.warn("[ChainPreview] shader backend unavailable, fallback to legacy"
            + " (configured=" + configured
            + ", selected=" + selectedId
            + (backendCreationFailure.isEmpty() ? "" : ", creationFailure=" + backendCreationFailure)
            + ", caps=" + (capabilities == null ? "null" : capabilities.describe()) + ")");
    }
}
