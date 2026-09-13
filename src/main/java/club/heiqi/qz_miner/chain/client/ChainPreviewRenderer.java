package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewBackendSelector;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlBindings;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlCapabilities;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewLegacyBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRenderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
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
    private ChainPreviewVisualSettings lastVisualSettings;
    private ChainPreviewDrawPlan.Visuals visuals = ChainPreviewDrawPlan.Visuals.BASELINE;

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
        lastVisualSettings = null;
        visuals = ChainPreviewDrawPlan.Visuals.BASELINE;
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

        if (!renderCache.isPreviewActive()) {
            clearMesh();
            return;
        }

        renderCache.refreshForCamera(
            RenderManager.renderPosX,
            RenderManager.renderPosY,
            RenderManager.renderPosZ,
            System.nanoTime());
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
                ChainPreviewDrawPlan plan = buildDrawPlan();
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

    /** 后端选择（纯函数决策 + 惰性创建；能力探测每 lifecycle 一次）。 */
    private ChainPreviewRenderBackend selectBackend() {
        if (capabilities == null) {
            capabilities = ChainPreviewGlCapabilities.detect();
        }
        String configured = configuredBackendId();
        if (lastConfiguredBackendId != null && !lastConfiguredBackendId.equals(configured)) {
            shaderAttemptFailed = false;
            shaderFallbackReported = false;
            disposeBackend();
        }
        lastConfiguredBackendId = configured;

        String selected = ChainPreviewBackendSelector.select(configured, capabilities, shaderAttemptFailed);
        if (backend != null && selected.equals(backend.id())) {
            return backend;
        }
        disposeBackend();
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
     * 后端工厂：T2a 只落地 legacy；shader 分档在 T2b 换成直接调用
     * ChainPreviewShaderBackend.create()（不用反射）。
     */
    private ChainPreviewRenderBackend createBackend(String id) {
        if (ChainPreviewBackendSelector.LEGACY.equals(id)) {
            return new ChainPreviewLegacyBackend();
        }
        return null;
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
        ChainPreviewRenderBackend fallback = createBackend(ChainPreviewBackendSelector.LEGACY);
        backend = fallback;
        return fallback;
    }

    /** 拓扑 / 颜色上传：同 generation + 同 stateRevision 只走颜色流。 */
    private void applyPublication(
            ChainPreviewRenderBackend active,
            ChainPreviewRenderCache.MeshPublication publication) {
        ChainPreviewMesh mesh = publication.getMesh();
        boolean colorOnly = !mesh.isEmpty()
            && publication.getGeneration() == uploadedGeneration
            && publication.getStateRevision() == uploadedStateRevision;
        if (colorOnly) {
            if (!active.uploadColors(mesh)) {
                active.uploadTopology(mesh);
                if (!mesh.isEmpty()) {
                    scaleCounters.recordTopologyUpload();
                }
            } else {
                scaleCounters.recordColorUpload();
            }
        } else {
            active.uploadTopology(mesh);
            if (!mesh.isEmpty()) {
                scaleCounters.recordTopologyUpload();
            }
        }
        uploadedGeneration = publication.getGeneration();
        uploadedStateRevision = publication.getStateRevision();
        activeMesh = mesh;
    }

    /**
     * 派生本帧 draw plan。
     *
     * <p>动画未实现（B3.1 时钟 / B3.3 逐波属下一批）：本轮恒 {@code animationU = 1}、
     * {@code waveEnds = null}，是登记在案的未交付项，不代表默认档已实现动画。
     * 索引顺序不等于 appearOrder 顺序，legacy 无法用索引段表达逐波，
     * wave 生长走 shader 的逐顶点 appearOrder 比较（T2b）；legacy 路径整体绘制。</p>
     */
    private ChainPreviewDrawPlan buildDrawPlan() {
        ChainPreviewMesh mesh = activeMesh == null ? ChainPreviewMesh.EMPTY : activeMesh;
        return ChainPreviewDrawPlan.derive(
            mesh,
            0,
            mesh.getIndexCount(),
            null,
            currentVisuals(),
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            mesh.getOriginX(),
            mesh.getOriginY(),
            mesh.getOriginZ(),
            scaleCounters.getRebuilds(),
            scaleCounters.getUploads());
    }

    /** 视觉参数快照：settings 引用未变时零分配复用。 */
    private ChainPreviewDrawPlan.Visuals currentVisuals() {
        ChainPreviewVisualSettings settings = renderCache.getVisualSettings();
        if (settings != lastVisualSettings) {
            lastVisualSettings = settings;
            visuals = settings == null
                ? ChainPreviewDrawPlan.Visuals.BASELINE
                : visualsFromSettings(settings);
        }
        return visuals;
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
            mapDepthChannel(settings.getDepthModeId()));
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
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glDepthMask(false);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                plan.getOriginX() - RenderManager.renderPosX,
                plan.getOriginY() - RenderManager.renderPosY,
                plan.getOriginZ() - RenderManager.renderPosZ);
            active.draw(plan);
        } finally {
            GL11.glPopMatrix();
        }
    }

    /**
     * 清理当前缓存的预览网格（空网格上传 = 清空索引）。
     *
     * <p>该路径可能在帧围栏之外被调用（预览未激活 / 世界为空 / 配置或生命周期早退），
     * 因此自带绑定围栏（T8-D2b）；空网格上传按接口契约不得触碰 GL，围栏是防御后端违规的加固。</p>
     */
    private void clearMesh() {
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
            + ", caps=" + (capabilities == null ? "null" : capabilities.describe()) + ")");
    }
}
