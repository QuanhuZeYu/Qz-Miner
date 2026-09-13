package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 连锁预览的纯数据绘制计划：描述“这一帧画什么”，不持有任何 GL 资源。
 *
 * <p>只保存索引范围、逐波生长波尾、平移基准、视觉参数、语义位掩码、深度通道与规模计数器；
 * 可在纯 JVM 内构造与断言。生产构造入口只有 {@link #derive}，
 * {@link #sanitized()} 负责把越界 / NaN / 乱序输入收窄且不抛异常（derive 内部已应用一次）。</p>
 *
 * <p>规模计数器语义：{@code rebuilds} = 渲染器发出的非空拓扑上传次数；
 * {@code uploads} = 拓扑 + 颜色流上传尝试次数（自渲染器创建起单调递增）。</p>
 *
 * <p>分配口径（T13-D4）：本类是每帧新建的短生命周期不可变对象；动画期间每帧 1 个 plan +
 * 1 个 {@link Visuals} 快照（{@link Visuals#withAnimationU} 在 u 未变化时返回自身）。
 * 为保持「纯数据 + 不可变」契约不做可变复用；动画 off / 完成后该路径零分配。</p>
 */
public final class ChainPreviewDrawPlan {

    /** 深度通道；XRAY 即现状（关闭深度测试）。 */
    public enum DepthChannel {
        XRAY,
        OCCLUDE,
        OUTLINE
    }

    /** 语义类别位掩码全开：不做类别过滤，等价现状。 */
    public static final int SEMANTIC_MASK_ALL = -1;

    /** 动画完成度：{@code animationU >= 1} 视为全部可见。 */
    public static final float ANIMATION_COMPLETE = 1.0F;

    /** barThickness 基线默认值（与 B0.1 配置默认同值）。 */
    public static final float DEFAULT_BAR_THICKNESS = 0.045F;

    public static final float MIN_BAR_THICKNESS = 0.001F;
    public static final float MAX_BAR_THICKNESS = 0.99F;
    public static final float MAX_MIN_SCREEN_WIDTH_PX = 8.0F;
    public static final float MIN_FADE_SPAN = 0.001F;

    private final int indexOffset;
    private final int indexCount;
    private final int quadCount;
    private final int[] waveEnds;
    private final int waveVisible;
    private final int visibleIndexCount;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final Visuals visuals;
    private final int semanticMask;
    private final int vertexCount;
    private final boolean truncated;
    private final long rebuilds;
    private final long uploads;

    /**
     * 视觉参数不可变快照：由 renderer 从 ChainPreviewVisualSettings 映射后传入。
     *
     * <p>深度淡出参数与 CPU 端 VisualParameters.alphaFor 同义：
     * {@code fadeStartRadius} 内为 {@code alphaStart}，{@code fadeEndRadius} 外为 {@code alphaEnd}，
     * 中间按归一化距离平方插值。</p>
     */
    public static final class Visuals {

        /** 屏幕最小宽度基线默认（§E：0.0 = 不钳制）。 */
        public static final float DEFAULT_MIN_SCREEN_WIDTH_PX = 0.0F;

        /** 距离淡出基线默认（与 ChainPreviewVisualSettings 的 NaN 兜底逐值同源）。 */
        public static final float DEFAULT_FADE_START_RADIUS = 2.0F;
        public static final float DEFAULT_FADE_END_RADIUS = 6.0F;
        public static final float DEFAULT_ALPHA_START = 0.78F;
        public static final float DEFAULT_ALPHA_END = 0.15F;

        /** settings 缺失 / 字段 NaN 时的最后防线：与 B0.1 配置默认逐项同值。 */
        public static final Visuals BASELINE = new Visuals(
            DEFAULT_BAR_THICKNESS,
            DEFAULT_MIN_SCREEN_WIDTH_PX,
            ANIMATION_COMPLETE,
            DEFAULT_FADE_START_RADIUS,
            DEFAULT_FADE_END_RADIUS,
            DEFAULT_ALPHA_START,
            DEFAULT_ALPHA_END,
            DepthChannel.XRAY,
            1.0F);

        /**
         * 语义颜色面（不可变）：由 renderer 从 ChainPreviewVisualSettings 经
         * {@link #fromConfig(String, int, int, int, int)} 映射后填入，shader 只读 plan。
         *
         * <p>{@code builtin} 档四色一律取 §D 基线常量 (0.25, 0.90, 1.00)（quantize 后 = 0x40E6FF），
         * 与 legacy CPU 颜色流逐位一致；{@code config} 档按位取配置色；null / 未知 sourceId 兜底 builtin。</p>
         */
        public static final class Colors {

            /** 颜色来源稳定 id：内置基线色。 */
            public static final String SOURCE_BUILTIN = "builtin";

            /** 颜色来源稳定 id：配置色。 */
            public static final String SOURCE_CONFIG = "config";

            /** §D builtin 基线常量 (0.25, 0.90, 1.00) 的 0xRRGGBB 量化值。 */
            public static final int BUILTIN_RGB = 0x40E6FF;

            /** 全类别 + 全字段基线常量色。 */
            public static final Colors BUILTIN =
                new Colors(SOURCE_BUILTIN, BUILTIN_RGB, BUILTIN_RGB, BUILTIN_RGB, BUILTIN_RGB);

            private final String sourceId;
            private final int primary;
            private final int secondary;
            private final int remote;
            private final int truncated;

            /**
             * 纯映射：{@code config} 档取配置色并按 0xFFFFFF 收窄；其余（builtin / null / 未知）一律基线常量。
             *
             * @param sourceId        颜色来源 id
             * @param primary         主模式颜色 0xRRGGBB
             * @param secondary       子模式颜色 0xRRGGBB
             * @param remote          远端预测颜色 0xRRGGBB
             * @param truncated       截断颜色 0xRRGGBB
             * @return 归一化颜色面
             */
            public static Colors fromConfig(
                    String sourceId, int primary, int secondary, int remote, int truncated) {
                if (SOURCE_CONFIG.equals(sourceId)) {
                    return new Colors(
                        SOURCE_CONFIG,
                        primary & 0xFFFFFF,
                        secondary & 0xFFFFFF,
                        remote & 0xFFFFFF,
                        truncated & 0xFFFFFF);
                }
                return BUILTIN;
            }

            public Colors(
                    String sourceId, int primary, int secondary, int remote, int truncated) {
                this.sourceId = SOURCE_CONFIG.equals(sourceId) ? SOURCE_CONFIG : SOURCE_BUILTIN;
                this.primary = primary & 0xFFFFFF;
                this.secondary = secondary & 0xFFFFFF;
                this.remote = remote & 0xFFFFFF;
                this.truncated = truncated & 0xFFFFFF;
            }

            /** @return 归一化来源 id：config / builtin */
            public String getSourceId() {
                return sourceId;
            }

            public int getPrimary() {
                return primary;
            }

            public int getSecondary() {
                return secondary;
            }

            public int getRemote() {
                return remote;
            }

            public int getTruncated() {
                return truncated;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Colors)) {
                    return false;
                }
                Colors that = (Colors) other;
                return primary == that.primary
                    && secondary == that.secondary
                    && remote == that.remote
                    && truncated == that.truncated
                    && sourceId.equals(that.sourceId);
            }

            @Override
            public int hashCode() {
                int result = sourceId.hashCode();
                result = 31 * result + primary;
                result = 31 * result + secondary;
                result = 31 * result + remote;
                result = 31 * result + truncated;
                return result;
            }

            @Override
            public String toString() {
                return "Colors{" + sourceId
                    + ", primary=0x" + Integer.toHexString(primary)
                    + ", secondary=0x" + Integer.toHexString(secondary)
                    + ", remote=0x" + Integer.toHexString(remote)
                    + ", truncated=0x" + Integer.toHexString(truncated)
                    + '}';
            }
        }

        private final float barThickness;
        private final float minScreenWidthPx;
        private final float animationU;
        private final float fadeStartRadius;
        private final float fadeEndRadius;
        private final float alphaStart;
        private final float alphaEnd;
        private final DepthChannel depthChannel;
        private final float fadeAlpha;
        private final Colors colors;

        /**
         * 简化构造：{@code fadeAlpha = 1}（无全局淡入淡出）+ builtin 颜色，保留既有调用点签名。
         */
        public Visuals(
                float barThickness,
                float minScreenWidthPx,
                float animationU,
                float fadeStartRadius,
                float fadeEndRadius,
                float alphaStart,
                float alphaEnd,
                DepthChannel depthChannel) {
            this(
                barThickness,
                minScreenWidthPx,
                animationU,
                fadeStartRadius,
                fadeEndRadius,
                alphaStart,
                alphaEnd,
                depthChannel,
                1.0F,
                Colors.BUILTIN);
        }

        public Visuals(
                float barThickness,
                float minScreenWidthPx,
                float animationU,
                float fadeStartRadius,
                float fadeEndRadius,
                float alphaStart,
                float alphaEnd,
                DepthChannel depthChannel,
                float fadeAlpha) {
            this(
                barThickness,
                minScreenWidthPx,
                animationU,
                fadeStartRadius,
                fadeEndRadius,
                alphaStart,
                alphaEnd,
                depthChannel,
                fadeAlpha,
                Colors.BUILTIN);
        }

        public Visuals(
                float barThickness,
                float minScreenWidthPx,
                float animationU,
                float fadeStartRadius,
                float fadeEndRadius,
                float alphaStart,
                float alphaEnd,
                DepthChannel depthChannel,
                float fadeAlpha,
                Colors colors) {
            this.barThickness = barThickness;
            this.minScreenWidthPx = minScreenWidthPx;
            this.animationU = animationU;
            this.fadeStartRadius = fadeStartRadius;
            this.fadeEndRadius = fadeEndRadius;
            this.alphaStart = alphaStart;
            this.alphaEnd = alphaEnd;
            this.depthChannel = depthChannel;
            this.fadeAlpha = fadeAlpha;
            this.colors = colors == null ? Colors.BUILTIN : colors;
        }

        public float getBarThickness() {
            return barThickness;
        }

        public float getMinScreenWidthPx() {
            return minScreenWidthPx;
        }

        /** @return 动画完成度 [0,1] */
        public float getAnimationU() {
            return animationU;
        }

        /**
         * @param nextAnimationU 本帧动画完成度
         * @return 仅替换动画完成度的新快照；u 相同返回自身（零分配）
         */
        public Visuals withAnimationU(float nextAnimationU) {
            if (Float.compare(animationU, nextAnimationU) == 0) {
                return this;
            }
            return new Visuals(
                barThickness,
                minScreenWidthPx,
                nextAnimationU,
                fadeStartRadius,
                fadeEndRadius,
                alphaStart,
                alphaEnd,
                depthChannel,
                fadeAlpha,
                colors);
        }

        /** @return 距离淡出起点（格），此距离内为 alphaStart */
        public float getFadeStartRadius() {
            return fadeStartRadius;
        }

        /** @return 距离淡出终点（格），此距离外为 alphaEnd */
        public float getFadeEndRadius() {
            return fadeEndRadius;
        }

        /** @return 近端 α */
        public float getAlphaStart() {
            return alphaStart;
        }

        /** @return 远端 α（距离淡出端点；全局乘子见 {@link #getFadeAlpha()}） */
        public float getAlphaEnd() {
            return alphaEnd;
        }

        /**
         * @return 全局淡入淡出乘子 [0,1]
         *
         * <p>由后端按各自机制施加，且只施加一次：shader 路径乘进顶点 alpha（{@code uFadeAlpha}），
         * legacy 固定管线用 1×1 白纹理 × GL_MODULATE 乘进逐顶点 α。
         * {@code alphaStart/alphaEnd} 保持距离淡出端点原值，不预乘本乘子（避免双乘 k²）。</p>
         */
        public float getFadeAlpha() {
            return fadeAlpha;
        }

        /** @return 语义颜色面，永不为 null */
        public Colors getColors() {
            return colors;
        }

        /**
         * 记录全局淡入淡出乘子（{@link #getAlphaStart()}/{@link #getAlphaEnd()} 保持原值）。
         *
         * <p>曲线性质（两条路径一致性的依据）：
         * {@code k · alphaFor(d, start, end, max, min) == alphaFor(d, start, end, k·max, k·min)} ——
         * 无论后端选择「端点缩放」还是「逐顶点乘子」，最终 α 都是同一乘法。</p>
         *
         * @param multiplier 乘子，运行时钳制到 [0,1]（NaN → 1）
         * @return 新快照；乘子未变化时返回自身（零分配）
         */
        public Visuals withFadeAlpha(float multiplier) {
            float safeMultiplier = clampFinite(multiplier, 0.0F, 1.0F, 1.0F);
            if (Float.compare(fadeAlpha, safeMultiplier) == 0) {
                return this;
            }
            return new Visuals(
                barThickness,
                minScreenWidthPx,
                animationU,
                fadeStartRadius,
                fadeEndRadius,
                alphaStart,
                alphaEnd,
                depthChannel,
                safeMultiplier,
                colors);
        }

        /** @return 深度通道，永不为 null */
        public DepthChannel getDepthChannel() {
            return depthChannel;
        }

        /**
         * 纯函数：距离 α 曲线（与 CPU 端 ChainPreviewMeshBuilder.VisualParameters.alphaFor 同形）。
         *
         * <p>{@code distance <= fadeStart} 返回 maxAlpha，{@code distance >= fadeEnd} 返回 minAlpha，
         * 中间按归一化距离平方插值；fadeEnd 与 fadeStart 之间至少保留 {@link #MIN_FADE_SPAN}，
         * α 收窄到 [0,1]。GLSL 端必须严格照同一形式实现，本函数是两条曲线一致性的可测基线。</p>
         *
         * @param distance  相机到顶点的距离（格）
         * @param fadeStart 淡出起点
         * @param fadeEnd   淡出终点
         * @param maxAlpha  近端 α
         * @param minAlpha  远端 α
         * @return [0,1] 内的 α
         */
        public static float alphaFor(
                double distance, float fadeStart, float fadeEnd, float maxAlpha, float minAlpha) {
            float start = Math.max(0.0F, fadeStart);
            float end = Math.max(start + MIN_FADE_SPAN, fadeEnd);
            float near = clampAlpha(maxAlpha);
            float far = clampAlpha(minAlpha);
            if (distance <= (double) start) {
                return near;
            }
            if (distance >= (double) end) {
                return far;
            }
            float normalized = (float) ((distance - (double) start) / ((double) end - (double) start));
            return near - (near - far) * (normalized * normalized);
        }

        private static float clampAlpha(float alpha) {
            return Math.max(0.0F, Math.min(1.0F, alpha));
        }

        /** @return 收窄 NaN / 越界后的视觉参数；本就规范时返回自身 */
        public Visuals sanitized() {
            float safeThickness = clampFinite(
                barThickness, MIN_BAR_THICKNESS, MAX_BAR_THICKNESS, DEFAULT_BAR_THICKNESS);
            float safeMinWidth = clampFinite(
                minScreenWidthPx, 0.0F, MAX_MIN_SCREEN_WIDTH_PX, DEFAULT_MIN_SCREEN_WIDTH_PX);
            float safeAnimationU = clampFinite(animationU, 0.0F, ANIMATION_COMPLETE, ANIMATION_COMPLETE);
            float safeFadeStart = clampFinite(
                fadeStartRadius, 0.0F, Float.MAX_VALUE, DEFAULT_FADE_START_RADIUS);
            float safeFadeEnd = clampFinite(
                fadeEndRadius, safeFadeStart + MIN_FADE_SPAN, Float.MAX_VALUE, DEFAULT_FADE_END_RADIUS);
            if (safeFadeEnd < safeFadeStart + MIN_FADE_SPAN) {
                safeFadeEnd = safeFadeStart + MIN_FADE_SPAN;
            }
            float safeAlphaStart = clampFinite(alphaStart, 0.0F, 1.0F, DEFAULT_ALPHA_START);
            float safeAlphaEnd = clampFinite(alphaEnd, 0.0F, 1.0F, DEFAULT_ALPHA_END);
            float safeFadeAlpha = clampFinite(fadeAlpha, 0.0F, 1.0F, 1.0F);
            DepthChannel safeChannel = depthChannel == null ? DepthChannel.XRAY : depthChannel;
            Colors safeColors = colors == null ? Colors.BUILTIN : colors;
            if (safeThickness == barThickness
                    && safeMinWidth == minScreenWidthPx
                    && safeAnimationU == animationU
                    && safeFadeStart == fadeStartRadius
                    && safeFadeEnd == fadeEndRadius
                    && safeAlphaStart == alphaStart
                    && safeAlphaEnd == alphaEnd
                    && safeFadeAlpha == fadeAlpha
                    && safeChannel == depthChannel
                    && safeColors == colors) {
                return this;
            }
            return new Visuals(
                safeThickness,
                safeMinWidth,
                safeAnimationU,
                safeFadeStart,
                safeFadeEnd,
                safeAlphaStart,
                safeAlphaEnd,
                safeChannel,
                safeFadeAlpha,
                safeColors);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Visuals)) {
                return false;
            }
            Visuals that = (Visuals) other;
            return Float.compare(barThickness, that.barThickness) == 0
                && Float.compare(minScreenWidthPx, that.minScreenWidthPx) == 0
                && Float.compare(animationU, that.animationU) == 0
                && Float.compare(fadeStartRadius, that.fadeStartRadius) == 0
                && Float.compare(fadeEndRadius, that.fadeEndRadius) == 0
                && Float.compare(alphaStart, that.alphaStart) == 0
                && Float.compare(alphaEnd, that.alphaEnd) == 0
                && Float.compare(fadeAlpha, that.fadeAlpha) == 0
                && depthChannel == that.depthChannel
                && colors.equals(that.colors);
        }

        @Override
        public int hashCode() {
            int result = Float.floatToIntBits(barThickness);
            result = 31 * result + Float.floatToIntBits(minScreenWidthPx);
            result = 31 * result + Float.floatToIntBits(animationU);
            result = 31 * result + Float.floatToIntBits(fadeStartRadius);
            result = 31 * result + Float.floatToIntBits(fadeEndRadius);
            result = 31 * result + Float.floatToIntBits(alphaStart);
            result = 31 * result + Float.floatToIntBits(alphaEnd);
            result = 31 * result + Float.floatToIntBits(fadeAlpha);
            result = 31 * result + (depthChannel == null ? 0 : depthChannel.hashCode());
            result = 31 * result + colors.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Visuals{barThickness=" + barThickness
                + ", minScreenWidthPx=" + minScreenWidthPx
                + ", animationU=" + animationU
                + ", fade=" + fadeStartRadius + ".." + fadeEndRadius
                + ", alpha=" + alphaStart + ".." + alphaEnd
                + ", fadeAlpha=" + fadeAlpha
                + ", depthChannel=" + depthChannel
                + ", " + colors
                + '}';
        }
    }

    /**
     * 原始构造：仅供同包测试构造畸形输入以验证 {@link #sanitized()}；生产入口是 {@link #derive}。
     */
    ChainPreviewDrawPlan(
            int indexOffset,
            int indexCount,
            int[] waveEnds,
            Visuals visuals,
            int semanticMask,
            int originX,
            int originY,
            int originZ,
            int vertexCount,
            boolean truncated,
            long rebuilds,
            long uploads) {
        this(
            indexOffset,
            indexCount,
            indexCount / 4,
            waveEnds,
            0,
            0,
            originX,
            originY,
            originZ,
            visuals == null ? Visuals.BASELINE : visuals,
            semanticMask,
            vertexCount,
            truncated,
            rebuilds,
            uploads);
    }

    private ChainPreviewDrawPlan(
            int indexOffset,
            int indexCount,
            int quadCount,
            int[] waveEnds,
            int waveVisible,
            int visibleIndexCount,
            int originX,
            int originY,
            int originZ,
            Visuals visuals,
            int semanticMask,
            int vertexCount,
            boolean truncated,
            long rebuilds,
            long uploads) {
        this.indexOffset = indexOffset;
        this.indexCount = indexCount;
        this.quadCount = quadCount;
        this.waveEnds = waveEnds;
        this.waveVisible = waveVisible;
        this.visibleIndexCount = visibleIndexCount;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.visuals = visuals;
        this.semanticMask = semanticMask;
        this.vertexCount = vertexCount;
        this.truncated = truncated;
        this.rebuilds = rebuilds;
        this.uploads = uploads;
    }

    /**
     * 纯函数：从网格与显式参数派生绘制计划。
     *
     * <p>索引范围先按 {@code mesh} 实际容量收窄；{@code waveEnds} 为逐波生长的独占索引上界，
     * 允许乱序 / 越界输入，由 {@link #sanitized()} 归一化为“严格递增、末项 ==
     * indexOffset + indexCount”；null / 空表示关闭动画。</p>
     *
     * @param mesh        当前已上传的网格，可为 null（视为空网格）
     * @param indexOffset 本次绘制索引起点（相对网格）
     * @param indexCount  本次绘制索引数量
     * @param waveEnds    波尾独占索引上界，null = 关闭
     * @param visuals     视觉参数，null → {@link Visuals#BASELINE}
     * @param semanticMask 语义类别位掩码
     * @param originX     平移基准 X（一般传网格 origin）
     * @param originY     平移基准 Y（一般传网格 origin）
     * @param originZ     平移基准 Z（一般传网格 origin）
     * @param rebuilds    累计拓扑上传次数（单调）
     * @param uploads     累计上传尝试次数（单调）
     * @return 已 sanitize 的绘制计划，永不返回 null
     */
    public static ChainPreviewDrawPlan derive(
            ChainPreviewMesh mesh,
            int indexOffset,
            int indexCount,
            int[] waveEnds,
            Visuals visuals,
            int semanticMask,
            int originX,
            int originY,
            int originZ,
            long rebuilds,
            long uploads) {
        ChainPreviewMesh source = mesh == null ? ChainPreviewMesh.EMPTY : mesh;
        int meshIndexCount = source.getIndexCount();
        int safeOffset = Math.max(0, Math.min(indexOffset, meshIndexCount));
        int safeCount = Math.max(0, Math.min(indexCount, meshIndexCount - safeOffset));
        return new ChainPreviewDrawPlan(
            safeOffset,
            safeCount,
            waveEnds,
            visuals == null ? Visuals.BASELINE : visuals,
            semanticMask,
            originX,
            originY,
            originZ,
            Math.max(0, source.getVertexFloatCount() / 3),
            source.isTruncated(),
            rebuilds,
            uploads).sanitized();
    }

    /**
     * 把本计划的越界 / NaN / 乱序输入收窄为规范形态；不抛异常。
     *
     * @return 已规范的计划；本计划本就规范时返回自身（零分配）
     */
    public ChainPreviewDrawPlan sanitized() {
        int normalizedOffset = Math.max(0, indexOffset);
        int safeCount = Math.max(0, indexCount);
        if (safeCount > Integer.MAX_VALUE - normalizedOffset) {
            safeCount = Integer.MAX_VALUE - normalizedOffset;
        }
        int normalizedCount = safeCount;
        int end = normalizedOffset + normalizedCount;
        int[] normalizedWaves = normalizeWaveEnds(waveEnds, normalizedOffset, end);
        Visuals normalizedVisuals = (visuals == null ? Visuals.BASELINE : visuals).sanitized();
        float normalizedAnimation = normalizedVisuals.getAnimationU();
        int normalizedVertexCount = Math.max(0, vertexCount);
        long normalizedRebuilds = Math.max(0L, rebuilds);
        long normalizedUploads = Math.max(0L, uploads);
        int normalizedQuadCount = normalizedCount / 4;
        int normalizedWaveVisible = countVisibleWaves(
            normalizedWaves, normalizedOffset, normalizedCount, normalizedAnimation);
        int normalizedVisibleIndices = normalizedWaves == null
            ? normalizedCount
            : (normalizedWaveVisible == 0
                ? 0
                : normalizedWaves[normalizedWaveVisible - 1] - normalizedOffset);

        if (normalizedOffset == indexOffset
                && normalizedCount == indexCount
                && normalizedQuadCount == quadCount
                && Arrays.equals(normalizedWaves, waveEnds)
                && normalizedWaveVisible == waveVisible
                && normalizedVisibleIndices == visibleIndexCount
                && normalizedVisuals == visuals
                && normalizedVertexCount == vertexCount
                && normalizedRebuilds == rebuilds
                && normalizedUploads == uploads) {
            return this;
        }
        return new ChainPreviewDrawPlan(
            normalizedOffset,
            normalizedCount,
            normalizedQuadCount,
            normalizedWaves,
            normalizedWaveVisible,
            normalizedVisibleIndices,
            originX,
            originY,
            originZ,
            normalizedVisuals,
            semanticMask,
            normalizedVertexCount,
            truncated,
            normalizedRebuilds,
            normalizedUploads);
    }

    /** @return 本次绘制索引起点（相对网格） */
    public int getIndexOffset() {
        return indexOffset;
    }

    /** @return 本次绘制索引数量 */
    public int getIndexCount() {
        return indexCount;
    }

    /** @return 本次绘制 quad 数量（indexCount / 4 向下取整） */
    public int getQuadCount() {
        return quadCount;
    }

    /**
     * @return 波尾独占索引上界的副本；null = 未启用逐波生长
     *
     * <p><b>索引段波表未启用</b>：当前网格先写 junction 相、后写 tube 相，索引顺序 != appearOrder
     * 顺序，索引段无法表达逐波；逐波生长由 shader 侧按 aAux 的 appearOrder 逐顶点比较实现，
     * plan 只提供 {@code animationU} 一个输入（Lead 裁定 2026-09-13）。
     * {@code waveEnds} 保留字段仅为未来「索引有序」场景；legacy 路径一律整体绘制，
     * 不生成排序索引副本。填充本字段会同时改变 {@code visibleIndexCount}，与逐顶点生长冲突。</p>
     */
    public int[] getWaveEnds() {
        return waveEnds == null ? null : waveEnds.clone();
    }

    /** @return 已完整通过的波数；未启用动画时为 0 */
    public int getWaveVisible() {
        return waveVisible;
    }

    /** @return 当前应绘制的索引数量；未启用动画时等于 {@link #getIndexCount()} */
    public int getVisibleIndexCount() {
        return visibleIndexCount;
    }

    /** @return 是否启用逐波生长（waveEnds 非空） */
    public boolean isAnimationEnabled() {
        return waveEnds != null;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public int getOriginZ() {
        return originZ;
    }

    /** @return 视觉参数快照，永不为 null */
    public Visuals getVisuals() {
        return visuals;
    }

    /** @return 条柱粗细（方块坐标单位） */
    public float getBarThickness() {
        return visuals.getBarThickness();
    }

    /** @return 条柱屏幕最小宽度（像素），0 = 关闭 */
    public float getMinScreenWidthPx() {
        return visuals.getMinScreenWidthPx();
    }

    /** @return 动画完成度 [0,1] */
    public float getAnimationU() {
        return visuals.getAnimationU();
    }

    /** @return 距离淡出起点（格） */
    public float getFadeStartRadius() {
        return visuals.getFadeStartRadius();
    }

    /** @return 距离淡出终点（格） */
    public float getFadeEndRadius() {
        return visuals.getFadeEndRadius();
    }

    /** @return 近端 α（距离淡出端点；全局乘子见 {@link #getFadeAlpha()}） */
    public float getAlphaStart() {
        return visuals.getAlphaStart();
    }

    /**
     * @return 全局淡入淡出乘子 [0,1]（恰施加一次：shader 乘进顶点 alpha，legacy 走纹理乘子）
     */
    public float getFadeAlpha() {
        return visuals.getFadeAlpha();
    }

    /** @return 远端 α（距离淡出端点；全局乘子见 {@link #getFadeAlpha()}） */
    public float getAlphaEnd() {
        return visuals.getAlphaEnd();
    }

    /**
     * @return 颜色来源归一化 id：{@code config} / {@code builtin}（null 与未知值兜底 builtin）
     */
    public String getColorSourceId() {
        return visuals.getColors().getSourceId();
    }

    /** @return 主模式颜色 0xRRGGBB（builtin 档为 §D 基线常量） */
    public int getColorPrimary() {
        return visuals.getColors().getPrimary();
    }

    /** @return 子模式颜色 0xRRGGBB（builtin 档为 §D 基线常量） */
    public int getColorSecondary() {
        return visuals.getColors().getSecondary();
    }

    /** @return 远端预测颜色 0xRRGGBB（builtin 档为 §D 基线常量） */
    public int getColorRemote() {
        return visuals.getColors().getRemote();
    }

    /** @return 截断颜色 0xRRGGBB（builtin 档为 §D 基线常量） */
    public int getColorTruncated() {
        return visuals.getColors().getTruncated();
    }

    /** @return 语义类别位掩码 */
    public int getSemanticMask() {
        return semanticMask;
    }

    /** @return 深度通道，永不为 null */
    public DepthChannel getDepthChannel() {
        return visuals.getDepthChannel();
    }

    /** @return 网格顶点数 */
    public int getVertexCount() {
        return vertexCount;
    }

    /** @return 网格是否因配额截断 */
    public boolean isTruncated() {
        return truncated;
    }

    /** @return 累计非空拓扑上传次数 */
    public long getRebuilds() {
        return rebuilds;
    }

    /** @return 累计上传尝试次数（拓扑 + 颜色流） */
    public long getUploads() {
        return uploads;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChainPreviewDrawPlan)) {
            return false;
        }
        ChainPreviewDrawPlan that = (ChainPreviewDrawPlan) other;
        return indexOffset == that.indexOffset
            && indexCount == that.indexCount
            && quadCount == that.quadCount
            && waveVisible == that.waveVisible
            && visibleIndexCount == that.visibleIndexCount
            && originX == that.originX
            && originY == that.originY
            && originZ == that.originZ
            && semanticMask == that.semanticMask
            && vertexCount == that.vertexCount
            && truncated == that.truncated
            && rebuilds == that.rebuilds
            && uploads == that.uploads
            && Arrays.equals(waveEnds, that.waveEnds)
            && visuals.equals(that.visuals);
    }

    @Override
    public int hashCode() {
        int result = indexOffset;
        result = 31 * result + indexCount;
        result = 31 * result + quadCount;
        result = 31 * result + Arrays.hashCode(waveEnds);
        result = 31 * result + waveVisible;
        result = 31 * result + visibleIndexCount;
        result = 31 * result + originX;
        result = 31 * result + originY;
        result = 31 * result + originZ;
        result = 31 * result + visuals.hashCode();
        result = 31 * result + semanticMask;
        result = 31 * result + vertexCount;
        result = 31 * result + (truncated ? 1 : 0);
        result = 31 * result + (int) (rebuilds ^ (rebuilds >>> 32));
        result = 31 * result + (int) (uploads ^ (uploads >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "ChainPreviewDrawPlan{offset=" + indexOffset
            + ", count=" + indexCount
            + ", quads=" + quadCount
            + ", waves=" + Arrays.toString(waveEnds)
            + ", waveVisible=" + waveVisible
            + ", visibleIndices=" + visibleIndexCount
            + ", origin=" + originX + "," + originY + "," + originZ
            + ", " + visuals
            + ", semanticMask=" + semanticMask
            + ", vertices=" + vertexCount
            + ", truncated=" + truncated
            + ", rebuilds=" + rebuilds
            + ", uploads=" + uploads
            + '}';
    }

    private static int[] normalizeWaveEnds(int[] raw, int start, int end) {
        if (raw == null || raw.length == 0 || end <= start) {
            return null;
        }
        int[] sorted = raw.clone();
        Arrays.sort(sorted);
        int[] normalized = new int[sorted.length];
        int size = 0;
        for (int value : sorted) {
            int clamped = Math.max(start, Math.min(value, end));
            if (clamped <= start) {
                continue;
            }
            if (size > 0 && normalized[size - 1] >= clamped) {
                continue;
            }
            normalized[size++] = clamped;
        }
        if (size == 0) {
            return null;
        }
        if (normalized[size - 1] != end) {
            if (size == normalized.length) {
                normalized = Arrays.copyOf(normalized, size + 1);
            }
            normalized[size++] = end;
        }
        return size == normalized.length ? normalized : Arrays.copyOf(normalized, size);
    }

    private static int countVisibleWaves(int[] waves, int start, int count, float animationU) {
        if (waves == null || count <= 0) {
            return 0;
        }
        int bound = start + (int) Math.floor((double) animationU * (double) count);
        int visible = 0;
        for (int wave : waves) {
            if (wave <= bound) {
                visible++;
            } else {
                break;
            }
        }
        return visible;
    }

    private static float clampFinite(float value, float min, float max, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
