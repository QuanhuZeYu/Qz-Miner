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

    /** 条柱粗细范围（接口冻结 §E）。 */
    private static final float BAR_THICKNESS_MIN = 0.005F;
    private static final float BAR_THICKNESS_MAX = 0.2F;
    /** 默认条柱粗细（= 历史硬编码 0.045）。 */
    private static final float BAR_THICKNESS_DEFAULT = 0.045F;
    /** 最小屏幕宽度兜底（基线档：关闭）。 */
    private static final float MIN_SCREEN_WIDTH_FALLBACK = 0.0F;

    private final float barThickness;
    private final float minScreenWidthPx;
    private final String depthModeId;
    private final String animationId;
    private final String animationPhaseId;
    private final String fadeModeId;
    private final String colorSourceId;
    private final String renderBackendId;
    private final int colorPrimary;
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
     * @param colorPrimary 主模式颜色 0xRRGGBB
     * @param colorSecondary 子模式颜色 0xRRGGBB
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
            int colorPrimary,
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
        this.barThickness = clampFloat(barThickness, BAR_THICKNESS_MIN, BAR_THICKNESS_MAX, BAR_THICKNESS_DEFAULT);
        this.minScreenWidthPx = clampFloat(minScreenWidthPx, 0.0F, 8.0F, MIN_SCREEN_WIDTH_FALLBACK);
        this.depthModeId = nonNull(depthModeId, PreviewDepthMode.defaultValue().id());
        this.animationId = nonNull(animationId, PreviewAnimationMode.defaultValue().id());
        this.animationPhaseId = nonNull(animationPhaseId, PreviewAnimationPhase.defaultValue().id());
        this.fadeModeId = nonNull(fadeModeId, PreviewFadeMode.defaultValue().id());
        this.colorSourceId = nonNull(colorSourceId, PreviewColorSource.defaultValue().id());
        this.renderBackendId = PreviewRenderBackend.fromId(renderBackendId) == null
            ? PreviewRenderBackend.defaultValue().id() : renderBackendId;
        this.colorPrimary = clampColor(colorPrimary);
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
            Config.clientPreviewColorPrimary,
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
            Config.clientPreviewMaxTargetsHardCap);
    }

    /** @return 条柱半厚（格） */
    public float getBarThickness() {
        return barThickness;
    }

    /** @return 屏幕最小宽度（像素） */
    public float getMinScreenWidthPx() {
        return minScreenWidthPx;
    }

    /** @return 深度通道稳定 id（xray / occlude / outline） */
    public String getDepthModeId() {
        return depthModeId;
    }

    /** @return 动画档位稳定 id（off / flow / wave） */
    public String getAnimationId() {
        return animationId;
    }

    /** @return 动画相位稳定 id（order / hash） */
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

    /** @return 主模式颜色 0xRRGGBB */
    public int getColorPrimary() {
        return colorPrimary;
    }

    /** @return 子模式颜色 0xRRGGBB */
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

    /** @return 是否启用截断可见信号 */
    public boolean isTruncationSignalEnabled() {
        return truncationSignal;
    }

    /** @return 预览目标上限硬顶 */
    public int getMaxTargetsHardCap() {
        return maxTargetsHardCap;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChainPreviewVisualSettings)) {
            return false;
        }
        ChainPreviewVisualSettings that = (ChainPreviewVisualSettings) other;
        return Float.compare(barThickness, that.barThickness) == 0
            && Float.compare(minScreenWidthPx, that.minScreenWidthPx) == 0
            && Float.compare(fadeRefreshDistance, that.fadeRefreshDistance) == 0
            && Float.compare(alphaFadeStartRadius, that.alphaFadeStartRadius) == 0
            && Float.compare(alphaFadeEndRadius, that.alphaFadeEndRadius) == 0
            && Float.compare(alphaStartValue, that.alphaStartValue) == 0
            && Float.compare(alphaEndValue, that.alphaEndValue) == 0
            && Float.compare(lodMinAlpha, that.lodMinAlpha) == 0
            && animationDurationMs == that.animationDurationMs
            && fadeFallbackMs == that.fadeFallbackMs
            && maxTargetsHardCap == that.maxTargetsHardCap
            && colorPrimary == that.colorPrimary
            && colorSecondary == that.colorSecondary
            && colorRemote == that.colorRemote
            && colorTruncated == that.colorTruncated
            && truncationSignal == that.truncationSignal
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
        result = 31 * result + depthModeId.hashCode();
        result = 31 * result + animationId.hashCode();
        result = 31 * result + animationPhaseId.hashCode();
        result = 31 * result + fadeModeId.hashCode();
        result = 31 * result + colorSourceId.hashCode();
        result = 31 * result + renderBackendId.hashCode();
        result = 31 * result + colorPrimary;
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
        return result;
    }

    @Override
    public String toString() {
        return "ChainPreviewVisualSettings{barThickness=" + barThickness
            + ", minScreenWidthPx=" + minScreenWidthPx
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
