package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.PreviewAnimationMode;
import club.heiqi.qz_miner.config.PreviewAnimationPhase;
import club.heiqi.qz_miner.config.PreviewColorSource;
import club.heiqi.qz_miner.config.PreviewDepthMode;
import club.heiqi.qz_miner.config.PreviewFadeMode;
import club.heiqi.qz_miner.config.PreviewLodMode;
import club.heiqi.qz_miner.config.PreviewRenderBackend;

/**
 * 连锁预览视觉参数的不可变快照（Lead 裁决：全项目唯一读取 clientPreview* 视觉键的入口）。
 *
 * <p>{@link #fromConfig()} 是生产路径唯一的配置读取面：构建线程与 renderer 只接收本对象，
 * 不得自行读取 Config。值相等（{@link #equals(Object)}）即观感无变化，供失效判定使用。</p>
 *
 * <p>枚举类配置以稳定文本 id 暴露，调用方不依赖 config 包类型；数值键一律做范围收窄，
 * null / NaN / 越界都不抛异常。</p>
 */
public final class ChainPreviewVisualSettings {

    /** 条柱粗细范围（配置键位与默认值（真源：QzMinerConfigDefaults））。 */
    private static final float BAR_THICKNESS_MIN = 0.005F;
    private static final float BAR_THICKNESS_MAX = 0.2F;
    /** 默认条柱粗细（= 历史硬编码 0.045）。 */
    private static final float BAR_THICKNESS_DEFAULT = 0.045F;
    /** 最小屏幕宽度兜底（基线档：关闭）。 */
    private static final float MIN_SCREEN_WIDTH_FALLBACK = 0.0F;
    /** 描边壳外扩宽度兜底（基线档：关闭）。 */
    private static final float OUTLINE_WIDTH_FALLBACK = 0.0F;
    /**
     * 连锁序亮度权重下限兜底（基线档：1.0 = 关闭本能力）。
     *
     * <p>与 {@link #OUTLINE_WIDTH_FALLBACK} 同一取向：兜底路径的语义是「拿不到配置 / 拿到的值非法」，
     * 取恒等值才等于历史观感；配置默认 0.55 是「用户选择了开启」，两者不得混用。</p>
     */
    private static final float ORDER_MIN_BRIGHTNESS_FALLBACK = 1.0F;
    /**
     * 内部结构亮度系数兜底（基线档：1.0 = 关闭本能力）。
     *
     * <p>与 {@link #ORDER_MIN_BRIGHTNESS_FALLBACK} 同一取向：兜底路径的语义是「拿不到配置 / 拿到的值非法」，
     * 取恒等值才等于历史观感；配置默认 0.65 是「用户选择了开启」，两者不得混用。</p>
     */
    private static final float INTERIOR_DIM_FALLBACK = 1.0F;

    private final float barThickness;
    private final float minScreenWidthPx;
    private final float outlineWidthPx;
    private final String depthModeId;
    private final String animationId;
    private final String animationPhaseId;
    private final String fadeModeId;
    private final String colorSourceId;
    private final String renderBackendId;
    private final int colorChain;
    private final int colorArea;
    private final int colorInteract;
    private final int colorSecondary;
    private final int colorRemote;
    private final int colorTruncated;
    private final int animationDurationMs;
    private final float fadeRefreshDistance;
    private final int fadeFallbackMs;
    private final float alphaFadeStartRadius;
    private final float alphaFadeEndRadius;
    private final float alphaStartValue;
    private final float alphaEndValue;
    private final String lodId;
    private final float lodMinAlpha;
    private final boolean truncationSignal;
    private final int maxTargetsHardCap;
    private final boolean suppressVanillaHighlight;
    private final boolean faceShading;
    private final float orderMinBrightness;
    private final float interiorDim;

    /** 最近一次发布的快照引用（零分配读取；volatile 读跨线程安全）。 */
    private static volatile ChainPreviewVisualSettings current;

    /**
     * @return 最近一次发布的设置快照（零分配 volatile 读）；未发布时用 {@link #fromConfig()} 初始化一次
     */
    public static ChainPreviewVisualSettings current() {
        ChainPreviewVisualSettings snapshot = current;
        if (snapshot == null) {
            snapshot = fromConfig();
            current = snapshot;
        }
        return snapshot;
    }

    /**
     * 发布新快照（配置提交或既有 1 Hz 采样点调用）。对象不可变，跨线程读取安全。
     *
     * @param settings 新快照，null 忽略
     */
    public static void publish(ChainPreviewVisualSettings settings) {
        if (settings != null) {
            current = settings;
        }
    }

    /**
     * 显式构造。
     *
     * @param barThickness 条柱半厚（格）
     * @param minScreenWidthPx 屏幕最小宽度（像素）
     * @param depthModeId 深度通道稳定 id
     * @param animationId 动画档位稳定 id
     * @param animationPhaseId 动画相位稳定 id
     * @param fadeModeId 淡出刷新档位稳定 id
     * @param colorSourceId 颜色来源稳定 id
     * @param renderBackendId 后端档位稳定 id（auto / shader / legacy）
     * @param colorChain CHAIN 大模式颜色 0xRRGGBB
     * @param colorArea AREA 大模式颜色 0xRRGGBB
     * @param colorInteract INTERACT 大模式颜色 0xRRGGBB
     * @param colorSecondary 扩展子模式颜色 0xRRGGBB
     * @param colorRemote 远端预测颜色 0xRRGGBB
     * @param colorTruncated 截断颜色 0xRRGGBB
     * @param animationDurationMs 动画时长（毫秒）
     * @param fadeRefreshDistance 淡出刷新位移阈值（格）
     * @param fadeFallbackMs 淡出兜底刷新（毫秒）
     * @param alphaFadeStartRadius 距离淡出起点（格）
     * @param alphaFadeEndRadius 距离淡出终点（格）
     * @param alphaStartValue 近处 alpha
     * @param alphaEndValue 远处 alpha
     * @param lodId LOD 档位稳定 id
     * @param lodMinAlpha LOD alpha 剔除阈值
     * @param truncationSignal 是否启用截断可见信号
     * @param maxTargetsHardCap 预览目标上限硬顶
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap) {
        this(
            barThickness, minScreenWidthPx, depthModeId, animationId, animationPhaseId, fadeModeId,
            colorSourceId, renderBackendId, colorChain, colorArea, colorInteract, colorSecondary,
            colorRemote, colorTruncated,
            animationDurationMs, fadeRefreshDistance, fadeFallbackMs, alphaFadeStartRadius,
            alphaFadeEndRadius, alphaStartValue, alphaEndValue, lodId, lodMinAlpha, truncationSignal,
            maxTargetsHardCap, false);
    }

    /**
     * 完整构造（T34 / B2.5 追加第 24 参：原版方块高亮抑制开关）。
     *
     * <p>其余字段语义、范围收窄与 {@code null} 兜底与 23 参构造完全一致；旧调用点保持原语义
     * （未显式传入时按默认关闭 false）。描边宽度未显式传入时按基线档 0（关闭描边）处理。</p>
     *
     * @param suppressVanillaHighlight 是否抑制原版方块选择框（B2.5；默认 false）
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap,
            boolean suppressVanillaHighlight) {
        this(
            barThickness, minScreenWidthPx, depthModeId, animationId, animationPhaseId, fadeModeId,
            colorSourceId, renderBackendId, colorChain, colorArea, colorInteract, colorSecondary,
            colorRemote, colorTruncated,
            animationDurationMs, fadeRefreshDistance, fadeFallbackMs, alphaFadeStartRadius,
            alphaFadeEndRadius, alphaStartValue, alphaEndValue, lodId, lodMinAlpha, truncationSignal,
            maxTargetsHardCap, suppressVanillaHighlight, OUTLINE_WIDTH_FALLBACK);
    }

    /**
     * 描边宽度构造（B3.x 第 25 参）：面朝向明暗未显式传入时按基线档关闭。
     *
     * <p>描边宽度收窄到 {@code [0, 8]}——与着色器 {@code clamp(uOutlineWidthPx, 0.0, 8.0)} 及
     * {@code ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX} 同区间，NaN / Infinity / 越界回落基线档 0；
     * 其余字段语义与 24 参构造完全一致。</p>
     *
     * @param outlineWidthPx 描边壳外扩宽度（物理像素；0 = 关闭描边，仅着色器 OUTLINE 深度档消费）
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap,
            boolean suppressVanillaHighlight,
            float outlineWidthPx) {
        this(barThickness, minScreenWidthPx, depthModeId, animationId, animationPhaseId, fadeModeId,
            colorSourceId, renderBackendId, colorChain, colorArea, colorInteract, colorSecondary,
            colorRemote, colorTruncated,
            animationDurationMs, fadeRefreshDistance, fadeFallbackMs, alphaFadeStartRadius,
            alphaFadeEndRadius, alphaStartValue, alphaEndValue, lodId, lodMinAlpha, truncationSignal,
            maxTargetsHardCap, suppressVanillaHighlight, outlineWidthPx, false);
    }

    /**
     * 26 参构造：连锁序权重未显式传入时按<b>基线档关闭</b>（{@code 1.0}）处理。
     *
     * <p>本构造曾是最完整的那个，现让位给追加了第 27 参的版本。不把 {@code orderMinBrightness} 默认成
     * 配置默认 0.55 是刻意的：「未显式传入」不能读作「用户选择了开启」，否则任何拿不到配置的调用点
     * 都会平白改变观感（同 {@link #OUTLINE_WIDTH_FALLBACK} 的取向）。</p>
     *
     * @param faceShadingEnabled 是否按面朝向烘焙明暗（默认 false = 等于接线前观感，逐字节不变）；
     *                           开启后 legacy 颜色流与着色器顶点色使用同一张亮度表
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap,
            boolean suppressVanillaHighlight,
            float outlineWidthPx,
            boolean faceShadingEnabled) {
        this(barThickness, minScreenWidthPx, depthModeId, animationId, animationPhaseId, fadeModeId,
            colorSourceId, renderBackendId, colorChain, colorArea, colorInteract, colorSecondary,
            colorRemote, colorTruncated,
            animationDurationMs, fadeRefreshDistance, fadeFallbackMs, alphaFadeStartRadius,
            alphaFadeEndRadius, alphaStartValue, alphaEndValue, lodId, lodMinAlpha, truncationSignal,
            maxTargetsHardCap, suppressVanillaHighlight, outlineWidthPx, faceShadingEnabled,
            ORDER_MIN_BRIGHTNESS_FALLBACK, INTERIOR_DIM_FALLBACK);
    }

    /**
     * 27 参构造：内部结构亮度系数未显式传入时按<b>基线档关闭</b>（{@code 1.0}）处理。
     *
     * <p>本构造曾是最完整的那个，现让位给追加了第 28 参的版本。不把 {@code interiorDim} 默认成
     * 配置默认 0.65 是刻意的：「未显式传入」不能读作「用户选择了开启」，否则任何拿不到配置的调用点
     * 都会平白改变观感（同 {@link #INTERIOR_DIM_FALLBACK} 的取向）。</p>
     *
     * @param orderMinBrightness 连锁序**亮度**权重下限（配置 {@code clientPreviewOrderMinBrightness}）；
     *                      {@code 1.0} = 关闭本能力，默认档 0.55 = 起点 1.0、最远 0.55
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap,
            boolean suppressVanillaHighlight,
            float outlineWidthPx,
            boolean faceShadingEnabled,
            float orderMinBrightness) {
        this(barThickness, minScreenWidthPx, depthModeId, animationId, animationPhaseId, fadeModeId,
            colorSourceId, renderBackendId, colorChain, colorArea, colorInteract, colorSecondary,
            colorRemote, colorTruncated,
            animationDurationMs, fadeRefreshDistance, fadeFallbackMs, alphaFadeStartRadius,
            alphaFadeEndRadius, alphaStartValue, alphaEndValue, lodId, lodMinAlpha, truncationSignal,
            maxTargetsHardCap, suppressVanillaHighlight, outlineWidthPx, faceShadingEnabled,
            orderMinBrightness, INTERIOR_DIM_FALLBACK);
    }

    /**
     * 完整构造（本轮追加第 28 参：内部结构亮度系数）。
     *
     * <p>取值面与收窄：{@code [0,1]} 内原样保留；NaN / Infinity / 越界回落
     * {@link #INTERIOR_DIM_FALLBACK}（= 1.0 = 恒等，等于接线前观感）。</p>
     *
     * @param interiorDim 内部结构亮度系数（配置 {@code clientPreviewInteriorDim}）；
     *                      {@code 1.0} = 关闭本能力，默认档 0.65 = 内部格线压暗到 65%、
     *                      贯通管面（外轮廓）保持 1.0；只乘颜色 rgb，不乘 alpha
     */
    public ChainPreviewVisualSettings(
            float barThickness,
            float minScreenWidthPx,
            String depthModeId,
            String animationId,
            String animationPhaseId,
            String fadeModeId,
            String colorSourceId,
            String renderBackendId,
            int colorChain,
            int colorArea,
            int colorInteract,
            int colorSecondary,
            int colorRemote,
            int colorTruncated,
            int animationDurationMs,
            float fadeRefreshDistance,
            int fadeFallbackMs,
            float alphaFadeStartRadius,
            float alphaFadeEndRadius,
            float alphaStartValue,
            float alphaEndValue,
            String lodId,
            float lodMinAlpha,
            boolean truncationSignal,
            int maxTargetsHardCap,
            boolean suppressVanillaHighlight,
            float outlineWidthPx,
            boolean faceShadingEnabled,
            float orderMinBrightness,
            float interiorDim) {
        this.barThickness = clampFloat(barThickness, BAR_THICKNESS_MIN, BAR_THICKNESS_MAX, BAR_THICKNESS_DEFAULT);
        this.minScreenWidthPx = clampFloat(minScreenWidthPx, 0.0F, 8.0F, MIN_SCREEN_WIDTH_FALLBACK);
        this.depthModeId = nonNull(depthModeId, PreviewDepthMode.defaultValue().id());
        this.animationId = nonNull(animationId, PreviewAnimationMode.defaultValue().id());
        this.animationPhaseId = nonNull(animationPhaseId, PreviewAnimationPhase.defaultValue().id());
        this.fadeModeId = nonNull(fadeModeId, PreviewFadeMode.defaultValue().id());
        this.colorSourceId = nonNull(colorSourceId, PreviewColorSource.defaultValue().id());
        this.renderBackendId = PreviewRenderBackend.fromId(renderBackendId) == null
            ? PreviewRenderBackend.defaultValue().id() : renderBackendId;
        this.colorChain = clampColor(colorChain);
        this.colorArea = clampColor(colorArea);
        this.colorInteract = clampColor(colorInteract);
        this.colorSecondary = clampColor(colorSecondary);
        this.colorRemote = clampColor(colorRemote);
        this.colorTruncated = clampColor(colorTruncated);
        this.animationDurationMs = clampInt(animationDurationMs, 0, 2000, 120);
        this.fadeRefreshDistance = clampFloat(fadeRefreshDistance, 0.0F, 8.0F, 0.5F);
        this.fadeFallbackMs = clampInt(fadeFallbackMs, 50, 5000, 250);
        this.alphaFadeStartRadius = clampFloat(alphaFadeStartRadius, 0.0F, Float.MAX_VALUE, 2.0F);
        this.alphaFadeEndRadius = Math.max(
            this.alphaFadeStartRadius + 0.001F,
            clampFloat(alphaFadeEndRadius, 0.0F, Float.MAX_VALUE, 6.0F));
        this.alphaStartValue = clampAlpha(alphaStartValue, 0.78F);
        this.alphaEndValue = clampAlpha(alphaEndValue, 0.15F);
        this.lodId = nonNull(lodId, PreviewLodMode.defaultValue().id());
        this.lodMinAlpha = clampFloat(lodMinAlpha, 0.0F, 1.0F, 0.05F);
        this.truncationSignal = truncationSignal;
        this.maxTargetsHardCap = clampInt(maxTargetsHardCap, 1, 4096, 4096);
        this.suppressVanillaHighlight = suppressVanillaHighlight;
        this.outlineWidthPx = clampFloat(outlineWidthPx, 0.0F, 8.0F, OUTLINE_WIDTH_FALLBACK);
        this.faceShading = faceShadingEnabled;
        this.orderMinBrightness = clampFloat(orderMinBrightness, 0.0F, 1.0F, ORDER_MIN_BRIGHTNESS_FALLBACK);
        this.interiorDim = clampFloat(interiorDim, 0.0F, 1.0F, INTERIOR_DIM_FALLBACK);
    }

    /**
     * 从顶层 Config 静态字段聚合快照。
     *
     * <p>缺键 / null 枚举 / NaN / 越界一律兜底收窄，不抛异常；本方法是生产路径唯一配置读取面。</p>
     *
     * @return 不可变视觉参数快照
     */
    public static ChainPreviewVisualSettings fromConfig() {
        return new ChainPreviewVisualSettings(
            clampFloat(Config.clientPreviewBarThickness, BAR_THICKNESS_MIN, BAR_THICKNESS_MAX, BAR_THICKNESS_DEFAULT),
            clampFloat(Config.clientPreviewMinScreenWidthPx, 0.0F, 8.0F, MIN_SCREEN_WIDTH_FALLBACK),
            Config.clientPreviewDepthMode == null
                ? PreviewDepthMode.defaultValue().id() : Config.clientPreviewDepthMode.id(),
            Config.clientPreviewAnimation == null
                ? PreviewAnimationMode.defaultValue().id() : Config.clientPreviewAnimation.id(),
            Config.clientPreviewAnimationPhase == null
                ? PreviewAnimationPhase.defaultValue().id() : Config.clientPreviewAnimationPhase.id(),
            Config.clientPreviewFadeMode == null
                ? PreviewFadeMode.defaultValue().id() : Config.clientPreviewFadeMode.id(),
            Config.clientPreviewColorSource == null
                ? PreviewColorSource.defaultValue().id() : Config.clientPreviewColorSource.id(),
            Config.clientPreviewRenderBackend == null
                ? PreviewRenderBackend.defaultValue().id() : Config.clientPreviewRenderBackend.id(),
            Config.clientPreviewColorChain,
            Config.clientPreviewColorArea,
            Config.clientPreviewColorInteract,
            Config.clientPreviewColorSecondary,
            Config.clientPreviewColorRemote,
            Config.clientPreviewColorTruncated,
            Config.clientPreviewAnimationDurationMs,
            (float) Config.clientPreviewFadeRefreshDistance,
            Config.clientPreviewFadeFallbackMs,
            (float) Config.clientPreviewAlphaFadeStartRadius,
            (float) Config.clientPreviewAlphaFadeEndRadius,
            (float) Config.clientPreviewAlphaStartValue,
            (float) Config.clientPreviewAlphaEndValue,
            Config.clientPreviewLod == null
                ? PreviewLodMode.defaultValue().id() : Config.clientPreviewLod.id(),
            (float) Config.clientPreviewLodMinAlpha,
            Config.clientPreviewTruncationSignal,
            Config.clientPreviewMaxTargetsHardCap,
            Config.clientPreviewSuppressVanillaHighlight,
            clampFloat(Config.clientPreviewOutlineWidthPx, 0.0F, 8.0F, OUTLINE_WIDTH_FALLBACK),
            Config.clientPreviewFaceShading,
            clampFloat(Config.clientPreviewOrderMinBrightness, 0.0F, 1.0F, ORDER_MIN_BRIGHTNESS_FALLBACK),
            clampFloat(Config.clientPreviewInteriorDim, 0.0F, 1.0F, INTERIOR_DIM_FALLBACK));
    }

    /** @return 条柱半厚（格） */
    public float getBarThickness() {
        return barThickness;
    }

    /** @return 屏幕最小宽度（像素）；0 = 关闭钳制 */
    public float getMinScreenWidthPx() {
        return minScreenWidthPx;
    }

    /** @return 描边壳外扩宽度（物理像素）；0 = 关闭描边（仅着色器 OUTLINE 深度档消费） */
    public float getOutlineWidthPx() {
        return outlineWidthPx;
    }

    /** @return 是否按面朝向烘焙明暗（face shading；默认 false = 两个后端与颜色流都逐字节等于现状） */
    public boolean isFaceShadingEnabled() {
        return faceShading;
    }

    /** @return 深度通道稳定 id（xray / occlude / outline） */
    public String getDepthModeId() {
        return depthModeId;
    }

    /** @return 动画档位稳定 id（off / flow / wave） */
    public String getAnimationId() {
        return animationId;
    }

    /**
     * @return 动画相位稳定 id（order / hash）
     *
     * <p><b>登记（假旋钮）</b>：当前没有任何生产消费者——渲染路径只读 animationId 与
     * animationDurationMs；该字段按保留的配置键位，B3.1 动画时钟接入后才生效。</p>
     */
    public String getAnimationPhaseId() {
        return animationPhaseId;
    }

    /** @return 淡出刷新档位稳定 id（timer / signal / gpu） */
    public String getFadeModeId() {
        return fadeModeId;
    }

    /** @return 颜色来源稳定 id（builtin / config） */
    public String getColorSourceId() {
        return colorSourceId;
    }

    /** @return 后端档位稳定 id（auto / shader / legacy） */
    public String getRenderBackendId() {
        return renderBackendId;
    }

    /** @return CHAIN 大模式颜色 0xRRGGBB */
    public int getColorChain() {
        return colorChain;
    }

    /** @return AREA 大模式颜色 0xRRGGBB */
    public int getColorArea() {
        return colorArea;
    }

    /** @return INTERACT 大模式颜色 0xRRGGBB */
    public int getColorInteract() {
        return colorInteract;
    }

    /** @return 扩展子模式颜色 0xRRGGBB */
    public int getColorSecondary() {
        return colorSecondary;
    }

    /** @return 远端预测颜色 0xRRGGBB */
    public int getColorRemote() {
        return colorRemote;
    }

    /** @return 截断颜色 0xRRGGBB */
    public int getColorTruncated() {
        return colorTruncated;
    }

    /** @return 动画时长（毫秒） */
    public int getAnimationDurationMs() {
        return animationDurationMs;
    }

    /** @return 淡出刷新位移阈值（格） */
    public float getFadeRefreshDistance() {
        return fadeRefreshDistance;
    }

    /** @return 淡出兜底刷新（毫秒） */
    public int getFadeFallbackMs() {
        return fadeFallbackMs;
    }

    /** @return 距离淡出起点（格） */
    public float getAlphaFadeStartRadius() {
        return alphaFadeStartRadius;
    }

    /** @return 距离淡出终点（格） */
    public float getAlphaFadeEndRadius() {
        return alphaFadeEndRadius;
    }

    /** @return 近处 alpha */
    public float getAlphaStartValue() {
        return alphaStartValue;
    }

    /** @return 远处 alpha */
    public float getAlphaEndValue() {
        return alphaEndValue;
    }

    /**
     * 距离淡出曲线（与 legacy CPU 路径同源的 quadratic 形式，供着色器按同一形式复现）。
     *
     * @param distance 目标到相机距离
     * @param fadeStart 淡出起点
     * @param fadeEnd 淡出终点
     * @param maxAlpha 近处 alpha
     * @param minAlpha 远处 alpha
     * @return 该距离的 alpha
     */
    public static float alphaFor(
            double distance, float fadeStart, float fadeEnd, float maxAlpha, float minAlpha) {
        double start = Math.max(0.0D, fadeStart);
        double end = Math.max(start + 0.001D, fadeEnd);
        float clampedMax = clampAlpha(maxAlpha, 1.0F);
        float clampedMin = clampAlpha(minAlpha, 0.0F);
        if (distance <= start) {
            return clampedMax;
        }
        if (distance >= end) {
            return clampedMin;
        }
        float normalized = (float) ((distance - start) / (end - start));
        return clampedMax - (clampedMax - clampedMin) * normalized * normalized;
    }

    /** @return LOD 档位稳定 id（off / auto） */
    public String getLodId() {
        return lodId;
    }

    /** @return LOD alpha 剔除阈值 */
    public float getLodMinAlpha() {
        return lodMinAlpha;
    }

    /**
     * @return 连锁序**亮度**权重下限（{@code [0,1]}）；{@code 1.0} = 关闭本能力（等于接线前观感）
     *
     * <p>消费链：{@link club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.Visuals}
     * → {@code ChainPreviewShaderBackend} 的 {@code uOrderMinBrightness} → {@code preview.vert} 的
     * {@code orderWeight} 分支，最终乘在<b>颜色亮度</b>上（{@code color * orderWeight}），不参与 alpha。
     * legacy 固定管线不消费本值（与描边宽度同理：只有着色器路径有该能力）。</p>
     */
    public float getOrderMinBrightness() {
        return orderMinBrightness;
    }

    /**
     * @return 内部结构亮度系数（{@code [0,1]}）；{@code 1.0} = 关闭本能力（等于接线前观感）
     *
     * <p>消费链：{@link club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.Visuals}
     * → {@code ChainPreviewShaderBackend} 的 {@code uInteriorDim} → {@code preview.vert} 的
     * 「内部结构压暗」分支，最终乘在<b>颜色 rgb</b> 上（{@code color = color * uInteriorDim}），
     * 不参与 alpha。压暗对象是 tubeEdge 未定义（{@code ChainPreviewMesh.AUX_UNDEFINED}）的
     * junction 补块 / 共享顶点，即「内部格线」；贯通管面（外轮廓）与描边 pass 都不消费本值。
     * legacy 固定管线不消费本值（与描边宽度同理：只有着色器路径有该能力）。</p>
     */
    public float getInteriorDim() {
        return interiorDim;
    }

    /** @return 是否启用截断可见信号 */
    public boolean isTruncationSignalEnabled() {
        return truncationSignal;
    }

    /** @return 预览目标上限硬顶 */
    public int getMaxTargetsHardCap() {
        return maxTargetsHardCap;
    }

    /**
     * @return 是否抑制原版方块选择框（B2.5 原版高亮协同；默认 false = 原版行为不变）
     */
    public boolean isSuppressVanillaHighlight() {
        return suppressVanillaHighlight;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChainPreviewVisualSettings)) {
            return false;
        }
        ChainPreviewVisualSettings that = (ChainPreviewVisualSettings) other;
        return Float.compare(barThickness, that.barThickness) == 0
            && Float.compare(minScreenWidthPx, that.minScreenWidthPx) == 0
            && Float.compare(outlineWidthPx, that.outlineWidthPx) == 0
            && Float.compare(fadeRefreshDistance, that.fadeRefreshDistance) == 0
            && Float.compare(alphaFadeStartRadius, that.alphaFadeStartRadius) == 0
            && Float.compare(alphaFadeEndRadius, that.alphaFadeEndRadius) == 0
            && Float.compare(alphaStartValue, that.alphaStartValue) == 0
            && Float.compare(alphaEndValue, that.alphaEndValue) == 0
            && Float.compare(lodMinAlpha, that.lodMinAlpha) == 0
            && animationDurationMs == that.animationDurationMs
            && fadeFallbackMs == that.fadeFallbackMs
            && maxTargetsHardCap == that.maxTargetsHardCap
            && colorChain == that.colorChain
            && colorArea == that.colorArea
            && colorInteract == that.colorInteract
            && colorSecondary == that.colorSecondary
            && colorRemote == that.colorRemote
            && colorTruncated == that.colorTruncated
            && truncationSignal == that.truncationSignal
            && suppressVanillaHighlight == that.suppressVanillaHighlight
            && faceShading == that.faceShading
            && Float.compare(orderMinBrightness, that.orderMinBrightness) == 0
            && Float.compare(interiorDim, that.interiorDim) == 0
            && depthModeId.equals(that.depthModeId)
            && animationId.equals(that.animationId)
            && animationPhaseId.equals(that.animationPhaseId)
            && fadeModeId.equals(that.fadeModeId)
            && colorSourceId.equals(that.colorSourceId)
            && renderBackendId.equals(that.renderBackendId)
            && lodId.equals(that.lodId);
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(barThickness);
        result = 31 * result + Float.floatToIntBits(minScreenWidthPx);
        result = 31 * result + Float.floatToIntBits(outlineWidthPx);
        result = 31 * result + depthModeId.hashCode();
        result = 31 * result + animationId.hashCode();
        result = 31 * result + animationPhaseId.hashCode();
        result = 31 * result + fadeModeId.hashCode();
        result = 31 * result + colorSourceId.hashCode();
        result = 31 * result + renderBackendId.hashCode();
        result = 31 * result + colorChain;
        result = 31 * result + colorArea;
        result = 31 * result + colorInteract;
        result = 31 * result + colorSecondary;
        result = 31 * result + colorRemote;
        result = 31 * result + colorTruncated;
        result = 31 * result + animationDurationMs;
        result = 31 * result + Float.floatToIntBits(fadeRefreshDistance);
        result = 31 * result + fadeFallbackMs;
        result = 31 * result + Float.floatToIntBits(alphaFadeStartRadius);
        result = 31 * result + Float.floatToIntBits(alphaFadeEndRadius);
        result = 31 * result + Float.floatToIntBits(alphaStartValue);
        result = 31 * result + Float.floatToIntBits(alphaEndValue);
        result = 31 * result + lodId.hashCode();
        result = 31 * result + Float.floatToIntBits(lodMinAlpha);
        result = 31 * result + (truncationSignal ? 1 : 0);
        result = 31 * result + maxTargetsHardCap;
        result = 31 * result + (suppressVanillaHighlight ? 1 : 0);
        result = 31 * result + (faceShading ? 1 : 0);
        result = 31 * result + Float.floatToIntBits(orderMinBrightness);
        result = 31 * result + Float.floatToIntBits(interiorDim);
        return result;
    }

    @Override
    public String toString() {
        return "ChainPreviewVisualSettings{barThickness=" + barThickness
            + ", minScreenWidthPx=" + minScreenWidthPx
            + ", outlineWidthPx=" + outlineWidthPx
            + ", depthMode=" + depthModeId
            + ", animation=" + animationId + "/" + animationPhaseId
            + ", fadeMode=" + fadeModeId
            + ", colorSource=" + colorSourceId
            + ", backend=" + renderBackendId
            + ", alphaFade=" + alphaFadeStartRadius + ".." + alphaFadeEndRadius
            + " (" + alphaStartValue + "->" + alphaEndValue + ")"
            + ", lod=" + lodId
            + ", truncationSignal=" + truncationSignal
            + ", maxTargetsHardCap=" + maxTargetsHardCap
            + ", suppressVanillaHighlight=" + suppressVanillaHighlight
            + ", faceShading=" + faceShading
            + ", orderMinBrightness=" + orderMinBrightness
            + ", interiorDim=" + interiorDim
            + "}";
    }

    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static float clampFloat(double value, float min, float max, float fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return (float) Math.max((double) min, Math.min((double) max, value));
    }

    private static int clampInt(int value, int min, int max, int fallback) {
        if (value <= 0 && min > 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float clampAlpha(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int clampColor(int value) {
        return value & 0xFFFFFF;
    }
}
