package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 本帧绘制范围、换基原点、视觉参数与统计的不可变计划，GL 资源由后端持有。
 * derive 从本帧输入派生并应用 sanitized 收窄，视觉快照在值相同时复用。
 * rebuilds 计非空拓扑上传，uploads 计拓扑与颜色上传尝试，均由 renderer 累积。
 */
public final class ChainPreviewDrawPlan {

    /** 深度通道，XRAY 关闭深度测试。 */
    public enum DepthChannel {
        XRAY,
        OCCLUDE,
        OUTLINE,
        DEPTH_OPAQUE
    }

    /** 语义类别位掩码全开，接受所有类别。 */
    public static final int SEMANTIC_MASK_ALL = -1;

    /** 动画完成度：{@code animationU >= 1} 视为全部可见。 */
    public static final float ANIMATION_COMPLETE = 1.0F;

    /** 条柱厚度基线默认值。 */
    public static final float DEFAULT_BAR_THICKNESS = 0.045F;

    public static final float MIN_BAR_THICKNESS = 0.001F;
    public static final float MAX_BAR_THICKNESS = 0.99F;
    public static final float MAX_MIN_SCREEN_WIDTH_PX = 8.0F;
    public static final float MIN_FADE_SPAN = 0.001F;

    /** 描边参考外扩宽度（物理像素）；生产宽度由视觉配置快照传入 withOutlinePass。 */
    public static final float OUTLINE_WIDTH_DEFAULT_PX = 1.5F;

    /** 描边外扩宽度收窄上限（物理像素）。 */
    public static final float MAX_OUTLINE_WIDTH_PX = 8.0F;

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
    private final boolean distanceFadeEnabled;
    private final int semanticMask;
    private final int vertexCount;
    private final boolean truncated;
    private final int culledTargetCount;
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

        /** 屏幕最小宽度基线默认（配置键位与默认值（真源：QzMinerConfigDefaults）：0.0 = 不钳制）。 */
        public static final float DEFAULT_MIN_SCREEN_WIDTH_PX = 0.0F;

        /** 配置不可用时使用恒等连锁序亮度，生产默认值由配置快照提供。 */
        public static final float DEFAULT_ORDER_MIN_BRIGHTNESS = 1.0F;

        /** 配置不可用时使用恒等内部亮度，生产默认值由配置快照提供。 */
        public static final float DEFAULT_INTERIOR_DIM = 1.0F;

        /** 距离淡出基线默认（与 ChainPreviewVisualSettings 的 NaN 兜底逐值同源）。 */
        public static final float DEFAULT_FADE_START_RADIUS = 2.0F;
        public static final float DEFAULT_FADE_END_RADIUS = 6.0F;
        public static final float DEFAULT_ALPHA_START = 0.78F;
        public static final float DEFAULT_ALPHA_END = 0.15F;

        /** 配置缺省基线：面明暗关闭、两项亮度取恒等值，其余沿配置默认。 */
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
         * {@link #fromConfig(String, int, int, int, int, int, int)} 映射后填入，shader 只读 plan。
         *
         * <p>{@code builtin} 档六槽取 {@code BUILTIN_*_RGB}（其中 CHAIN 槽 quantize 后 = 0x40E6FF，
         * 与 legacy CPU 颜色流逐位一致——见 {@code ChainPreviewShaderMath.builtinColorTable()}），
         * 默认即按大模式可区分；{@code config} 档按位取配置色；null / 未知 sourceId 兜底 builtin。</p>
         */
        public static final class Colors {

            /** 颜色来源稳定 id：内置基线色。 */
            public static final String SOURCE_BUILTIN = "builtin";

            /** 颜色来源稳定 id：配置色。 */
            public static final String SOURCE_CONFIG = "config";

            /** 色槽数：与 {@code ChainPreviewShaderMath.PALETTE_SLOT_COUNT}、preview.vert 的 uColor* 同源。 */
            public static final int SLOT_COUNT = 6;

            /**
             * CHAIN 语义色的 RGB 量化标识，供相等比较与诊断。
             * shader uniform 和 legacy 颜色流使用精确 float 常量 0.25F/0.9F/1.0F，保留原色精度。
             */
            public static final int BUILTIN_CHAIN_RGB = 0x40E6FF;

            /** AREA 使用红橙色，与 INTERACT 绿及截断琥珀区分，并保留爆破的暖色语义。 */
            public static final int BUILTIN_AREA_RGB = 0xE8503C;
            /** builtin 档 INTERACT 槽（范围交互）：色相明确偏绿（H≈135°），与 CHAIN 的青（H≈188°）区分。 */
            public static final int BUILTIN_INTERACT_RGB = 0x58E07A;
            /** builtin 档扩展子模式槽。 */
            public static final int BUILTIN_SUB_MODE_RGB = 0xB08CFF;
            /** builtin 档远端槽。 */
            public static final int BUILTIN_REMOTE_RGB = 0x8FA9D0;
            /** 截断保留槽使用琥珀色，与 AREA 红区分。 */
            public static final int BUILTIN_TRUNCATED_RGB = 0xF8C858;

            /** builtin 档按模式区分的六槽配色。 */
            public static final Colors BUILTIN = new Colors(
                SOURCE_BUILTIN,
                BUILTIN_CHAIN_RGB,
                BUILTIN_AREA_RGB,
                BUILTIN_INTERACT_RGB,
                BUILTIN_SUB_MODE_RGB,
                BUILTIN_REMOTE_RGB,
                BUILTIN_TRUNCATED_RGB);

            private final String sourceId;
            private final int chain;
            private final int area;
            private final int interact;
            private final int secondary;
            private final int remote;
            private final int truncated;

            /**
             * 纯映射：{@code config} 档取配置色并按 0xFFFFFF 收窄；其余（builtin / null / 未知）一律 builtin 六槽。
             *
             * @param sourceId        颜色来源 id
             * @param chain           CHAIN 大模式颜色 0xRRGGBB
             * @param area            AREA 大模式颜色 0xRRGGBB
             * @param interact        INTERACT 大模式颜色 0xRRGGBB
             * @param secondary       扩展子模式颜色 0xRRGGBB
             * @param remote          远端预测颜色 0xRRGGBB
             * @param truncated       截断颜色 0xRRGGBB
             * @return 归一化颜色面
             */
            public static Colors fromConfig(
                    String sourceId, int chain, int area, int interact,
                    int secondary, int remote, int truncated) {
                if (SOURCE_CONFIG.equals(sourceId)) {
                    return new Colors(
                        SOURCE_CONFIG,
                        chain & 0xFFFFFF,
                        area & 0xFFFFFF,
                        interact & 0xFFFFFF,
                        secondary & 0xFFFFFF,
                        remote & 0xFFFFFF,
                        truncated & 0xFFFFFF);
                }
                return BUILTIN;
            }

            public Colors(
                    String sourceId, int chain, int area, int interact,
                    int secondary, int remote, int truncated) {
                this.sourceId = SOURCE_CONFIG.equals(sourceId) ? SOURCE_CONFIG : SOURCE_BUILTIN;
                this.chain = chain & 0xFFFFFF;
                this.area = area & 0xFFFFFF;
                this.interact = interact & 0xFFFFFF;
                this.secondary = secondary & 0xFFFFFF;
                this.remote = remote & 0xFFFFFF;
                this.truncated = truncated & 0xFFFFFF;
            }

            /** @return 归一化来源 id：config / builtin */
            public String getSourceId() {
                return sourceId;
            }

            /** @return CHAIN 大模式颜色 0xRRGGBB */
            public int getChain() {
                return chain;
            }

            /** @return AREA 大模式颜色 0xRRGGBB */
            public int getArea() {
                return area;
            }

            /** @return INTERACT 大模式颜色 0xRRGGBB */
            public int getInteract() {
                return interact;
            }

            /** @return 扩展子模式颜色 0xRRGGBB */
            public int getSecondary() {
                return secondary;
            }

            /** @return 远端预测颜色 0xRRGGBB */
            public int getRemote() {
                return remote;
            }

            /** @return 截断颜色 0xRRGGBB */
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
                return chain == that.chain
                    && area == that.area
                    && interact == that.interact
                    && secondary == that.secondary
                    && remote == that.remote
                    && truncated == that.truncated
                    && sourceId.equals(that.sourceId);
            }

            @Override
            public int hashCode() {
                int result = sourceId.hashCode();
                result = 31 * result + chain;
                result = 31 * result + area;
                result = 31 * result + interact;
                result = 31 * result + secondary;
                result = 31 * result + remote;
                result = 31 * result + truncated;
                return result;
            }

            @Override
            public String toString() {
                return "Colors{" + sourceId
                    + ", chain=0x" + Integer.toHexString(chain)
                    + ", area=0x" + Integer.toHexString(area)
                    + ", interact=0x" + Integer.toHexString(interact)
                    + ", secondary=0x" + Integer.toHexString(secondary)
                    + ", remote=0x" + Integer.toHexString(remote)
                    + ", truncated=0x" + Integer.toHexString(truncated)
                    + '}';
            }
        }

        /**
         * LOD 的不可变生效档位与 alpha 阈值，构建器按双阈值滞回决定目标包含集。
         * off 保留全部目标，auto 启用剔除；未知档位归一为 off。
         */
        public static final class Lod {

            /** LOD 档位稳定 id：关闭（默认）。 */
            public static final String MODE_OFF = "off";

            /** LOD 档位稳定 id：自动。 */
            public static final String MODE_AUTO = "auto";

            /** 关闭档单例：无剔除。 */
            public static final Lod OFF = new Lod(MODE_OFF, 0.0F);

            private final String modeId;
            private final float minAlpha;

            /**
             * 纯映射：只认 {@code auto}，其余（off / null / 未知）一律 off。
             *
             * @param modeId   LOD 档位稳定 id
             * @param minAlpha alpha 剔除阈值（运行时钳制到 [0,1]，NaN → 0）
             * @return 归一化 LOD 状态
             */
            public static Lod fromConfig(String modeId, float minAlpha) {
                if (MODE_AUTO.equals(modeId)) {
                    return new Lod(MODE_AUTO, minAlpha);
                }
                return OFF;
            }

            public Lod(String modeId, float minAlpha) {
                this.modeId = MODE_AUTO.equals(modeId) ? MODE_AUTO : MODE_OFF;
                this.minAlpha = clampFinite(minAlpha, 0.0F, 1.0F, 0.0F);
            }

            /** @return 归一化档位 id：off / auto */
            public String getModeId() {
                return modeId;
            }

            /** @return 生效 alpha 剔除阈值（off 档为 0 = 未启用） */
            public float getMinAlpha() {
                return minAlpha;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Lod)) {
                    return false;
                }
                Lod that = (Lod) other;
                return modeId.equals(that.modeId) && Float.compare(minAlpha, that.minAlpha) == 0;
            }

            @Override
            public int hashCode() {
                return 31 * modeId.hashCode() + Float.floatToIntBits(minAlpha);
            }

            @Override
            public String toString() {
                return "Lod{" + modeId + ", minAlpha=" + minAlpha + '}';
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
        private final Lod lod;
        private final boolean outlineShell;
        private final float outlineWidthPx;
        private final boolean faceShading;
        private final float orderMinBrightness;
        private final float interiorDim;

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
                colors,
                Lod.OFF);
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
                Colors colors,
                Lod lod) {
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
                colors,
                lod,
                false,
                0.0F);
        }

        /** 正宽度且 shell 开启时形成描边段，其余输入归一为普通段；面明暗使用关闭值。 */
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
                Colors colors,
                Lod lod,
                boolean outlineShell,
                float outlineWidthPx) {
            this(barThickness, minScreenWidthPx, animationU, fadeStartRadius, fadeEndRadius,
                alphaStart, alphaEnd, depthChannel, fadeAlpha, colors, lod,
                outlineShell, outlineWidthPx, false);
        }

        /** 补充面明暗开关，两项亮度沿兼容构造的恒等值。 */
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
                Colors colors,
                Lod lod,
                boolean outlineShell,
                float outlineWidthPx,
                boolean faceShading) {
            this(barThickness, minScreenWidthPx, animationU, fadeStartRadius, fadeEndRadius,
                alphaStart, alphaEnd, depthChannel, fadeAlpha, colors, lod,
                outlineShell, outlineWidthPx, faceShading, DEFAULT_ORDER_MIN_BRIGHTNESS,
                DEFAULT_INTERIOR_DIM);
        }

        /** 补充连锁序亮度，内部亮度沿兼容构造的恒等值。 */
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
                Colors colors,
                Lod lod,
                boolean outlineShell,
                float outlineWidthPx,
                boolean faceShading,
                float orderMinBrightness) {
            this(barThickness, minScreenWidthPx, animationU, fadeStartRadius, fadeEndRadius,
                alphaStart, alphaEnd, depthChannel, fadeAlpha, colors, lod,
                outlineShell, outlineWidthPx, faceShading, orderMinBrightness, DEFAULT_INTERIOR_DIM);
        }

        /** 完整视觉构造；内部亮度作用于主体格线 rgb，描边与 alpha 分别处理。 */
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
                Colors colors,
                Lod lod,
                boolean outlineShell,
                float outlineWidthPx,
                boolean faceShading,
                float orderMinBrightness,
                float interiorDim) {
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
            this.lod = lod == null ? Lod.OFF : lod;
            this.outlineShell = outlineShell && outlineWidthPx > 0.0F;
            this.outlineWidthPx = this.outlineShell ? outlineWidthPx : 0.0F;
            this.faceShading = faceShading;
            this.orderMinBrightness = orderMinBrightness;
            this.interiorDim = interiorDim;
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
            // 替换动画进度时保留面明暗，使用普通非描边段。
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
                colors,
                lod,
                false,
                0.0F,
                faceShading,
                orderMinBrightness,
                interiorDim);
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
         * {@code alphaStart/alphaEnd} 保留距离淡出端点值，由后端施加一次包络乘子。</p>
         */
        public float getFadeAlpha() {
            return fadeAlpha;
        }

        /** @return 语义颜色面，永不为 null */
        public Colors getColors() {
            return colors;
        }

        /** @return LOD 状态，永不为 null（默认 {@link Lod#OFF}） */
        public Lod getLod() {
            return lod;
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
                colors,
                lod,
                false,
                0.0F,
                faceShading,
                orderMinBrightness,
                interiorDim);
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

        /** @return 本段是否为 OUTLINE 描边壳段（B3.x；仅 OUTLINE 档首段为 true） */
        public boolean isOutlineShell() {
            return outlineShell;
        }

        /** @return 描边壳外扩宽度（物理像素）；非壳段恒 0 */
        public float getOutlineWidthPx() {
            return outlineWidthPx;
        }

        /** @return 是否按面朝向烘焙明暗（face shading；false = 后端与颜色流都逐字节等于现状） */
        public boolean isFaceShadingEnabled() {
            return faceShading;
        }

        /**
         * @return 连锁序**亮度**权重下限；{@code 1.0} = 关闭本能力（后端传 1.0，GLSL 不进入该分支）
         *
         * <p>消费链：{@code ChainPreviewShaderBackend#orderMinBrightnessFor} → {@code uOrderMinBrightness}
         * → preview.vert 的 {@code orderWeight}（乘在颜色亮度上，alpha 只由距离淡出 × 生长 × 包络决定）。
         * legacy 固定管线不消费本值。</p>
         */
        public float getOrderMinBrightness() {
            return orderMinBrightness;
        }

        /**
         * @return 内部结构亮度系数；{@code 1.0} = 关闭本能力（后端传 1.0，GLSL 不进入该分支）
         *
         * <p>消费链：{@code ChainPreviewShaderBackend#interiorDimFor} → {@code uInteriorDim}
         * → preview.vert 的内部结构压暗分支（乘在内部格线的颜色 rgb 上，alpha 只由距离淡出 × 生长 ×
         * 包络决定；描边 pass 不参与，见该分支注释）。legacy 固定管线不消费本值。</p>
         */
        public float getInteriorDim() {
            return interiorDim;
        }

        /**
         * 派生：标记 / 取消描边壳段（B3.x 真描边）。
         *
         * <p>只改本对象的描边两参，其余字段原样复制；规范化口径与完整构造一致
         * （{@code shell && widthPx > 0} 才算壳段，宽度收窄到 [0, {@link ChainPreviewDrawPlan#MAX_OUTLINE_WIDTH_PX}]）。
         * 形态未变化时返回自身（零分配）。</p>
         *
         * @param shell   是否描边壳段
         * @param widthPx 外扩宽度（物理像素）
         * @return 派生视觉参数或自身
         */
        public Visuals withOutlinePass(boolean shell, float widthPx) {
            float safeWidth = clampFinite(widthPx, 0.0F, MAX_OUTLINE_WIDTH_PX, 0.0F);
            boolean nextShell = shell && safeWidth > 0.0F;
            float nextWidth = nextShell ? safeWidth : 0.0F;
            if (nextShell == outlineShell && nextWidth == outlineWidthPx) {
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
                fadeAlpha,
                colors,
                lod,
                nextShell,
                nextWidth,
                faceShading,
                orderMinBrightness,
                interiorDim);
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
            Lod safeLod = lod == null ? Lod.OFF : lod;
            float safeOutlineWidth = clampFinite(outlineWidthPx, 0.0F, MAX_OUTLINE_WIDTH_PX, 0.0F);
            boolean safeOutlineShell = outlineShell && safeOutlineWidth > 0.0F;
            if (!safeOutlineShell) {
                safeOutlineWidth = 0.0F;
            }
            float safeOrderMinBrightness = clampFinite(
                orderMinBrightness, 0.0F, 1.0F, DEFAULT_ORDER_MIN_BRIGHTNESS);
            float safeInteriorDim = clampFinite(interiorDim, 0.0F, 1.0F, DEFAULT_INTERIOR_DIM);
            if (safeThickness == barThickness
                    && safeMinWidth == minScreenWidthPx
                    && safeAnimationU == animationU
                    && safeFadeStart == fadeStartRadius
                    && safeFadeEnd == fadeEndRadius
                    && safeAlphaStart == alphaStart
                    && safeAlphaEnd == alphaEnd
                    && safeFadeAlpha == fadeAlpha
                    && safeChannel == depthChannel
                    && safeColors == colors
                    && safeLod == lod
                    && safeOutlineShell == outlineShell
                    && safeOutlineWidth == outlineWidthPx
                    && safeOrderMinBrightness == orderMinBrightness
                    && safeInteriorDim == interiorDim) {
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
                safeColors,
                safeLod,
                safeOutlineShell,
                safeOutlineWidth,
                faceShading,
                safeOrderMinBrightness,
                safeInteriorDim);
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
                && outlineShell == that.outlineShell
                && Float.compare(outlineWidthPx, that.outlineWidthPx) == 0
                && faceShading == that.faceShading
                && Float.compare(orderMinBrightness, that.orderMinBrightness) == 0
                && Float.compare(interiorDim, that.interiorDim) == 0
                && colors.equals(that.colors)
                && lod.equals(that.lod);
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
            result = 31 * result + (outlineShell ? 1 : 0);
            result = 31 * result + Float.floatToIntBits(outlineWidthPx);
            result = 31 * result + (faceShading ? 1 : 0);
            result = 31 * result + Float.floatToIntBits(orderMinBrightness);
            result = 31 * result + Float.floatToIntBits(interiorDim);
            result = 31 * result + colors.hashCode();
            result = 31 * result + lod.hashCode();
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
                + ", outlineShell=" + outlineShell + (outlineShell ? "@" + outlineWidthPx + "px" : "")
                + ", faceShading=" + faceShading
                + ", orderMinBrightness=" + orderMinBrightness
                + ", interiorDim=" + interiorDim
                + ", " + colors
                + ", " + lod
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
            int culledTargetCount,
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
            culledTargetCount,
            rebuilds,
            uploads,
            true);
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
            int culledTargetCount,
            long rebuilds,
            long uploads,
            boolean distanceFadeEnabled) {
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
        this.distanceFadeEnabled = distanceFadeEnabled;
        this.semanticMask = semanticMask;
        this.vertexCount = vertexCount;
        this.truncated = truncated;
        this.culledTargetCount = culledTargetCount;
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
            Math.max(0, source.getCulledTargetCount()),
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
        int normalizedCulledTargets = Math.max(0, culledTargetCount);
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
                && normalizedCulledTargets == culledTargetCount
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
            normalizedCulledTargets,
            normalizedRebuilds,
            normalizedUploads,
            distanceFadeEnabled);
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

    /**
     * 派生：把本计划切成描边壳段 / 主体段（B3.x 真描边）。
     *
     * <p>只替换 {@link Visuals} 的描边两参，其余字段（索引范围、origin、语义掩码、计数）原样保留；
     * 描边形态未变化时返回自身（零分配）。XRAY / OCCLUDE 不调用本方法，逐字节等于现状。</p>
     *
     * <p>壳段判定即 {@code shell && widthPx > 0}：renderer 只在 OUTLINE 档调用，
     * 宽度取 读取面单通道（真源：ChainPreviewVisualSettings）（配置 {@code client.clientPreviewOutlineWidthPx}）。</p>
     *
     * <p><b>本方法只把「本计划」标记为非壳段，不会改变 stage 划分</b>——OUTLINE 档仍是 2 段
     * （{@link ChainPreviewDepthPass#stageCount}）。宽度 0 的收敛发生在档位解析处：
     * {@link ChainPreviewDepthPass#resolvePass} 把 OUTLINE 降为单遍 XRAY，否则同一几何会被
     * 两段各画一遍而叠色。此前本注释称「宽度 0 收敛为非壳段即关闭描边、无需改动 stage 划分」，
     * 漏掉了 stage 仍为 2 这一点，与实现不符。</p>
     *
     * @param shell   是否描边壳段
     * @param widthPx 外扩宽度（物理像素；&lt;= 0 / NaN 收敛为非壳段）
     * @return 派生计划或自身
     */
    public ChainPreviewDrawPlan withOutlinePass(boolean shell, float widthPx) {
        return withStageVisuals(visuals.withOutlinePass(shell, widthPx), distanceFadeEnabled);
    }

    /**
     * 派生阶段的距离透明度策略；关闭时仅移除距离衰减，保留生长、淡入淡出与颜色。
     * shader 消费有效 alpha 端点，legacy 替换颜色流中已烘焙的距离 alpha。
     *
     * @param enabled 是否保留距离透明度
     * @return 派生计划；策略未变时返回自身
     */
    public ChainPreviewDrawPlan withDistanceFade(boolean enabled) {
        return withStageVisuals(visuals, enabled);
    }

    /** @return 本阶段是否消费距离透明度 */
    public boolean isDistanceFadeEnabled() {
        return distanceFadeEnabled;
    }

    private ChainPreviewDrawPlan withStageVisuals(Visuals derivedVisuals, boolean distanceFade) {
        if (derivedVisuals == visuals && distanceFade == distanceFadeEnabled) {
            return this;
        }
        return new ChainPreviewDrawPlan(
            indexOffset,
            indexCount,
            quadCount,
            waveEnds,
            waveVisible,
            visibleIndexCount,
            originX,
            originY,
            originZ,
            derivedVisuals,
            semanticMask,
            vertexCount,
            truncated,
            culledTargetCount,
            rebuilds,
            uploads,
            distanceFade);
    }

    /** @return 本计划是否为 OUTLINE 描边壳段（B3.x） */
    public boolean isOutlineShell() {
        return visuals.isOutlineShell();
    }

    /** @return 描边壳外扩宽度（物理像素）；非壳段恒 0 */
    public float getOutlineWidthPx() {
        return visuals.getOutlineWidthPx();
    }

    /** @return 是否按面朝向烘焙明暗（face shading；false = 着色器不进入乘色分支，逐字节等于现状） */
    public boolean isFaceShadingEnabled() {
        return visuals.isFaceShadingEnabled();
    }

    /** @return 连锁序**亮度**权重下限；1.0 = 关闭本能力（着色器不进入 orderWeight 分支，逐值等于现状） */
    public float getOrderMinBrightness() {
        return visuals.getOrderMinBrightness();
    }

    /** @return 内部结构亮度系数；1.0 = 关闭本能力（着色器不进入内部压暗分支，逐值等于现状） */
    public float getInteriorDim() {
        return visuals.getInteriorDim();
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

    /** @return 本阶段的近端 α；关闭距离透明度时为 1，全局乘子另行施加 */
    public float getAlphaStart() {
        return distanceFadeEnabled ? visuals.getAlphaStart() : 1.0F;
    }

    /**
     * @return 全局淡入淡出乘子 [0,1]（恰施加一次：shader 乘进顶点 alpha，legacy 走纹理乘子）
     */
    public float getFadeAlpha() {
        return visuals.getFadeAlpha();
    }

    /** @return 本阶段的远端 α；关闭距离透明度时为 1，全局乘子另行施加 */
    public float getAlphaEnd() {
        return distanceFadeEnabled ? visuals.getAlphaEnd() : 1.0F;
    }

    /**
     * @return 颜色来源归一化 id：{@code config} / {@code builtin}（null 与未知值兜底 builtin）
     */
    public String getColorSourceId() {
        return visuals.getColors().getSourceId();
    }

    /** @return CHAIN 大模式颜色 0xRRGGBB（builtin 档为六槽表槽 0） */
    public int getColorChain() {
        return visuals.getColors().getChain();
    }

    /** @return AREA 大模式颜色 0xRRGGBB（builtin 档为六槽表槽 1） */
    public int getColorArea() {
        return visuals.getColors().getArea();
    }

    /** @return INTERACT 大模式颜色 0xRRGGBB（builtin 档为六槽表槽 2） */
    public int getColorInteract() {
        return visuals.getColors().getInteract();
    }

    /** @return 扩展子模式颜色 0xRRGGBB（builtin 档为六槽表槽 3） */
    public int getColorSecondary() {
        return visuals.getColors().getSecondary();
    }

    /** @return 远端预测颜色 0xRRGGBB（builtin 档为六槽表槽 4） */
    public int getColorRemote() {
        return visuals.getColors().getRemote();
    }

    /** @return 截断颜色 0xRRGGBB（builtin 档为六槽表槽 5） */
    public int getColorTruncated() {
        return visuals.getColors().getTruncated();
    }

    /** @return LOD 生效档位 id：off（默认）/ auto；null 与未知值兜底 off */
    public String getLodId() {
        return visuals.getLod().getModeId();
    }

    /** @return LOD 生效 alpha 剔除阈值 [0,1]；off 档为 0（未启用） */
    public float getLodMinAlpha() {
        return visuals.getLod().getMinAlpha();
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

    /**
     * @return 本网格构建期因 LOD（alpha ≤ lodMinAlpha）被剔除、未生成几何的目标（条柱）数；
     *         lod=off 恒 0。单位与 {@code ChainPreviewMesh.getCulledTargetCount()} 一致（目标数，
     *         不含 quad 估算）
     */
    public int getCulledTargetCount() {
        return culledTargetCount;
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
            && culledTargetCount == that.culledTargetCount
            && truncated == that.truncated
            && rebuilds == that.rebuilds
            && uploads == that.uploads
            && distanceFadeEnabled == that.distanceFadeEnabled
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
        result = 31 * result + (distanceFadeEnabled ? 1 : 0);
        result = 31 * result + semanticMask;
        result = 31 * result + vertexCount;
        result = 31 * result + culledTargetCount;
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
            + ", distanceFade=" + distanceFadeEnabled
            + ", semanticMask=" + semanticMask
            + ", vertices=" + vertexCount
            + ", culledTargets=" + culledTargetCount
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
