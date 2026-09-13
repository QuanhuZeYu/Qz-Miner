package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;

/**
 * 预览着色器的 CPU 参考模型：与 {@code preview.vert} / {@code preview.frag} 里同名同形的纯函数。
 *
 * <p><strong>为什么要有这个类</strong>：着色器能不能编译、能不能出正确像素只能真机验证，
 * 但「数值形状」可以在纯 JVM 内断言。把 GLSL 的公式在 Java 里原样复刻一份，再与 CPU 端
 * {@link ChainPreviewMeshBuilder.VisualParameters#alphaFor} 做逐点比对，就能在离线阶段证明
 * <b>legacy 与 shader 两档的距离淡出曲线同形</b>；否则用户切换 backend 会看到两套观感。</p>
 *
 * <p>本类不加载任何 GL，可在 headless 测试中直接调用。GLSL 侧改动时必须同步本类，
 * {@code ChainPreviewShaderContractTest} 用两套独立实现（Java 参考模型 + Java 内实现 GLSL
 * 表达式的镜像）互证，而不是断言源码字符串。</p>
 */
public final class ChainPreviewShaderMath {

    /** 淡出曲线的分母下限，与 GLSL 的 {@code max(uFadeEnd - uFadeStart, 1e-4)} 一致。 */
    private static final float FADE_SPAN_EPSILON = 1e-4F;

    /** 横向投影长度下限，与 GLSL 的 {@code clamp(worldLateral, 0.05, 1.0)} 一致。 */
    private static final float MIN_LATERAL_PROJECTION = 0.05F;

    /** 横向放大上限，与 GLSL 的 {@code clamp(..., 1.0, 64.0)} 一致。 */
    private static final float MAX_LATERAL_WIDEN = 64.0F;

    /** 片元丢弃阈值，与 GLSL 的 {@code vColor.a <= 0.0039} 一致。 */
    private static final float FRAGMENT_DISCARD_ALPHA = 0.0039F;

    /** legacy 基线条柱厚度（= ChainPreviewMeshBuilder 的默认值，供便捷重载使用）。 */
    private static final float DEFAULT_BAR_THICKNESS = 0.045F;

    /** appearOrder 未定义哨兵（接口冻结 §A：0xFFFF）。 */
    public static final float APPEAR_ORDER_UNDEFINED = 65535.0F;

    /** legacy 基线常量色 R（= ChainPreviewMeshBuilder.BASE_RED）。 */
    public static final float BUILTIN_COLOR_RED = 0.25F;
    /** legacy 基线常量色 G（= ChainPreviewMeshBuilder.BASE_GREEN）。 */
    public static final float BUILTIN_COLOR_GREEN = 0.9F;
    /** legacy 基线常量色 B（= ChainPreviewMeshBuilder.BASE_BLUE）。 */
    public static final float BUILTIN_COLOR_BLUE = 1.0F;

    /**
     * builtin 调色板（静态复用，只读）：四槽都是精确基线常量。
     *
     * <p>本表会在每帧 uniform 路径上被取用，因此<strong>静态复用、不 clone</strong>（F4）；
     * 调用方只能读。builtin 档刻意不经 8bit 量化往返——{@code round(0.9×255)=230}、
     * {@code 230/255=0.9019608} 与 0.9F 差 1.96e-3，那正是「逐字节等于现状」不允许的色差。</p>
     */
    private static final float[][] BUILTIN_COLOR_TABLE = {
        {BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE},
        {BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE},
        {BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE},
        {BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE},
    };

    /** 颜色来源稳定 id：内置（legacy 现状兼容色）。 */
    public static final String COLOR_SOURCE_BUILTIN = "builtin";
    /** 颜色来源稳定 id：配置（四色来自 clientPreviewColor*）。 */
    public static final String COLOR_SOURCE_CONFIG = "config";

    // ---------------------------------------------------------------- §D 语义类别（冻结）

    // 语义类别常量**单一真源**：session-core 的 ChainPreviewSemanticClass（F3）。
    // 本类不再自造 id；GLSL 侧因无法共享 Java 常量而保留字面量，注释互指。
    /** 0 PRIMARY_LOCAL 主模式本地预测 → 主色。 */
    public static final int SEMANTIC_PRIMARY_LOCAL = ChainPreviewSemanticClass.PRIMARY_LOCAL;
    /** 1 SUB_MODE_LOCAL 子模式本地预测 → 子模式色。 */
    public static final int SEMANTIC_SUB_MODE_LOCAL = ChainPreviewSemanticClass.SUB_MODE_LOCAL;
    /** 2 REMOTE_PREDICTED 远端预测 → 远端色。 */
    public static final int SEMANTIC_REMOTE_PREDICTED = ChainPreviewSemanticClass.REMOTE_PREDICTED;
    /** 3 TRUNCATED 截断（本轮数据源不产出，保留合法分支）→ 截断色。 */
    public static final int SEMANTIC_TRUNCATED = ChainPreviewSemanticClass.TRUNCATED;
    /** 4 DEFERRED 待执行（未启用）→ 主色兜底。 */
    public static final int SEMANTIC_DEFERRED = ChainPreviewSemanticClass.DEFERRED;
    /** 5 EXECUTED 已执行（未启用）→ 主色兜底。 */
    public static final int SEMANTIC_EXECUTED = ChainPreviewSemanticClass.EXECUTED;
    /** 255 UNDEFINED 未定义 → 主色兜底。 */
    public static final int SEMANTIC_UNDEFINED = ChainPreviewSemanticClass.UNDEFINED;

    /** 调色板下标：主色。 */
    public static final int PALETTE_PRIMARY = 0;
    /** 调色板下标：子模式色。 */
    public static final int PALETTE_SECONDARY = 1;
    /** 调色板下标：远端色。 */
    public static final int PALETTE_REMOTE = 2;
    /** 调色板下标：截断色。 */
    public static final int PALETTE_TRUNCATED = 3;

    private ChainPreviewShaderMath() {}

    /**
     * 单位深度上的「像素 / 世界单位」缩放（GLSL {@code uPixelScale}）。
     *
     * <p>透视投影矩阵第 [1][1] 元素为 {@code cot(fovY/2)}，NDC 的 [-1,1] 映射到视口高度的
     * 全部像素，故像素数 = {@code projection11 × viewportHeight / 2}。</p>
     *
     * @param projection11   GL 投影矩阵 [1][1]（列主序下标 5）的绝对值
     * @param viewportHeight 渲染线程内读到的 GL_VIEWPORT 高度
     * @return uPixelScale；非法输入返回 0（shader 侧等价于关闭钳制）
     */
    public static float pixelScale(float projection11, int viewportHeight) {
        if (!isFinite(projection11) || viewportHeight <= 0) {
            return 0.0F;
        }
        return Math.abs(projection11) * (float) viewportHeight * 0.5F;
    }

    /**
     * 距离淡出：与 CPU 端 {@code VisualParameters.alphaFor} 的 quadratic 形状逐点一致。
     *
     * @param distance  相机到顶点的距离（世界单位）
     * @param fadeStart 起始半径内恒为 maxAlpha
     * @param fadeEnd   结束半径外恒为 minAlpha
     * @param maxAlpha  最大 alpha
     * @param minAlpha  最小 alpha
     * @return alpha
     */
    public static float fadeAlpha(float distance, float fadeStart, float fadeEnd, float maxAlpha, float minAlpha) {
        if (distance <= fadeStart) {
            return maxAlpha;
        }
        if (distance >= fadeEnd) {
            return minAlpha;
        }
        float span = Math.max(fadeEnd - fadeStart, FADE_SPAN_EPSILON);
        float t = (distance - fadeStart) / span;
        return maxAlpha - (maxAlpha - minAlpha) * t * t;
    }

    /**
     * 相机到顶点的距离：GLSL 侧用 {@code length(uOriginRel + aPos)}，此处按 meshOrigin + 局部坐标复刻。
     *
     * @param originX meshOrigin 的世界 X
     * @param originY meshOrigin 的世界 Y
     * @param originZ meshOrigin 的世界 Z
     * @param localX  顶点相对 meshOrigin 的 X
     * @param localY  顶点相对 meshOrigin 的 Y
     * @param localZ  顶点相对 meshOrigin 的 Z
     * @param cameraX 相机世界 X（{@code RenderManager.renderPosX}）
     * @param cameraY 相机世界 Y
     * @param cameraZ 相机世界 Z
     * @return 欧氏距离
     */
    public static float vertexDistance(
            double originX, double originY, double originZ,
            float localX, float localY, float localZ,
            double cameraX, double cameraY, double cameraZ) {
        double dx = originX + localX - cameraX;
        double dy = originY + localY - cameraY;
        double dz = originZ + localZ - cameraZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 逐波生长权重：与 {@code preview.vert} 的
     * {@code clamp(uAnimProgress * totalTargets - floor(min(order, totalTargets)), 0, 1)} 同形。
     *
     * <p>判据 {@code order <= round(u × totalTargets)} 被写成「序号格之差」，因此：</p>
     * <ul>
     *   <li>{@code u = 0} ⇒ 恒 0（全隐，order=0 的顶点也要等 u 超过 1/total）；</li>
     *   <li>{@code u} 单调递增 ⇒ 每个顶点的权重单调不减（不存在「先显后隐」）；</li>
     *   <li>{@code u >= 1} 或 {@code totalTargets <= 0} ⇒ 整段可见（GLSL 侧直接跳过比较）。</li>
     * </ul>
     *
     * @param growthEnabled     生长是否启用（GLSL 侧为 {@code uAnimProgress < 1 && uAppearSpan > 0}）
     * @param animationProgress 出现序号归一化进度 [0,1]
     * @param appearOrder       顶点出现序号；{@code >= 0xFFFF} 视为已出现
     * @param totalTargets      同代目标总数（<= 0 表示无序号信息）
     * @return 顶点可见权重 [0,1]
     */
    public static float growthWeight(
            boolean growthEnabled, float animationProgress, float appearOrder, float totalTargets) {
        if (!growthEnabled || !(totalTargets > 0.0F)) {
            return 1.0F;
        }
        // 0xFFFF = 未定义序号，必须最先判定：GLSL 是「appearOrder < 65535.0」才进入比较，
        // 否则 growth 保持初值 1.0。放在 u>=1 之后会让「u=0 + 未定义序号」被误判为不可见，
        // 在链路上留下空洞（Lead 明确要求按「已出现」处理）。
        if (appearOrder >= APPEAR_ORDER_UNDEFINED) {
            return 1.0F;
        }
        // 与 GLSL 逐项同形：u >= 1 走「整段可见」出口（GLSL 侧根本不会进入该比较分支）。
        if (animationProgress >= 1.0F) {
            return 1.0F;
        }
        float orderFloor = (float) Math.floor(Math.min(appearOrder, totalTargets));
        return clamp(animationProgress * totalTargets - orderFloor, 0.0F, 1.0F);
    }

    /**
     * 该进度下「已出现」的最大序号（诊断与测试用）。
     *
     * <p><strong>取整规则（以 GLSL 主路径语义为准，T13-D3）</strong>：共 {@code totalTargets} 个
     * 目标时，GLSL 只让 {@code order < u × total} 的顶点进入可见（等价于 {@code u × total} 恰好
     * 落到某一格边界时该格算「刚进入」）。因此最大可见序号 = {@code ceil(u × total) − 1}，
     * 这与逐顶点式 {@code growth = clamp(u × total − floor(order), 0, 1) > 0} 严格一致；
     * 早先的 {@code floor(u × total + 0.5)} 会多算一个（total=10、u=0.25 → 3 vs 2）。</p>
     *
     * @param animationProgress 归一化进度
     * @param totalTargets      目标总数
     * @return 已出现的最大出现序号；无序号信息时返回 Integer.MAX_VALUE
     */
    public static int visibleOrderCount(float animationProgress, float totalTargets) {
        if (!(totalTargets > 0.0F)) {
            return Integer.MAX_VALUE;
        }
        if (animationProgress >= 1.0F) {
            // 与逐顶点式严格一致：u=1 时最大可见序号仍是 total-1（不是 total）。
            return (int) totalTargets - 1;
        }
        if (!(animationProgress > 0.0F)) {
            return -1;
        }
        // 极小 epsilon 抵消浮点表示误差（u=1/3 时 u*3 可能略小于 1）。
        return (int) Math.ceil(animationProgress * totalTargets - 1.0e-9D) - 1;
    }

    /**
     * 最终顶点 alpha：距离淡出 × 淡入淡出包络（B3.2）。
     *
     * <p>GLSL 侧 {@code vColor.a = fade * growth * uFadeAlpha}，其中 fade 是
     * {@link #fadeAlpha} 的 quadratic 曲线。{@code fadeAlpha = 1} 时必须与现状逐值一致
     * （乘 1 不改变任何结果），这是「不启用动画档 ⇒ 观感不变」的可断言形式。</p>
     *
     * @param distanceBasedAlpha 距离淡出 alpha（{@link #fadeAlpha} 的结果）
     * @param fadeAlpha          淡入淡出包络 [0,1]；1 = 完全不透明
     * @return 最终 alpha
     */
    public static float finalAlpha(float distanceBasedAlpha, float fadeAlpha) {
        // NaN 收敛为「不透明」而非传播：异常配置不得让整条链路静默消失。
        if (Float.isNaN(fadeAlpha)) {
            return distanceBasedAlpha;
        }
        return distanceBasedAlpha * clamp(fadeAlpha, 0.0F, 1.0F);
    }

    /**
     * 描边宽度像素上限：**单一真源在 plan**（{@link ChainPreviewDrawPlan#MAX_OUTLINE_WIDTH_PX}），
     * 本处只是给参考模型一个稳定入口，避免两处各写一份 8.0F 而漂移。
     */
    public static final float MAX_OUTLINE_WIDTH_PX = ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX;

    /** 横向半格：相邻条柱中心距 1 格，任一侧占用不得超过它。 */
    public static final float HALF_TILE = 0.5F;

    /**
     * 描边宽度收敛（host 侧，与 GLSL 内防御性 clamp 同口径）。
     *
     * @param requestedPx 请求宽度（物理像素）；NaN 或 &lt;= 0 视为关闭
     * @return [0, {@link #MAX_OUTLINE_WIDTH_PX}] 的宽度；关闭时精确为 0
     */
    public static float outlineWidthPx(float requestedPx) {
        if (Float.isNaN(requestedPx) || !(requestedPx > 0.0F)) {
            return 0.0F;
        }
        return Math.min(requestedPx, MAX_OUTLINE_WIDTH_PX);
    }

    /**
     * 描边外扩量（世界单位），与 {@code preview.vert} 的换算逐式同形。
     *
     * <p>{@code widthPx <= 0}（默认档 / xray / occlude / OUTLINE 主体 pass）必须返回<b>精确 0</b>，
     * 这样顶点位移恒等、逐值等于现状。</p>
     *
     * @param widthPx             描边宽度（物理像素）
     * @param pixelsPerWorldUnit  横向「像素 / 世界单位」（= uPixelScale / depth × 横向投影）
     * @return 外扩的世界量；关闭时为 0
     */
    public static float outlineWidenWorld(float widthPx, float pixelsPerWorldUnit, float barThickness) {
        float px = outlineWidthPx(widthPx);
        if (px <= 0.0F || !(pixelsPerWorldUnit > 0.0F) || !isFinite(pixelsPerWorldUnit)) {
            return 0.0F;
        }
        return Math.min(px / pixelsPerWorldUnit, maxOutlineWorld(barThickness));
    }

    /**
     * 便捷重载：按 legacy 默认厚度 {@value #DEFAULT_BAR_THICKNESS} 取世界上界。
     *
     * <p><strong>厚度相关断言必须用三参版本</strong>——本重载把上界固定成默认厚度对应的
     * {@code 0.455}，无法覆盖 {@code barThickness} 变化（例如 0.2 ⇒ 0.3、&ge;0.5 ⇒ 0）。
     * 保留它只为兼容既有调用点。</p>
     *
     * @param widthPx            描边宽度（物理像素）
     * @param pixelsPerWorldUnit 横向「像素 / 世界单位」
     * @return 外扩的世界量
     */
    public static float outlineWidenWorld(float widthPx, float pixelsPerWorldUnit) {
        return outlineWidenWorld(widthPx, pixelsPerWorldUnit, DEFAULT_BAR_THICKNESS);
    }

    /**
     * 描边外扩的世界量上界：{@code max(0, 0.5 − barThickness)}（与 GLSL 逐式同形）。
     *
     * <p><strong>为什么不是固定 0.5</strong>：条柱自身已占用 {@code barThickness}，相邻条柱中心距
     * 1 格，两侧同时外扩后间隙 = {@code 1 − 2×(barThickness + widen)}。固定 0.5 在默认厚度 0.045
     * 下给出 {@code 1 − 2×0.545 = −0.09} 格（相邻条柱粘连）；取 {@code 0.5 − barThickness} 后
     * 上界处间隙恰好为 0。</p>
     *
     * @param barThickness 条柱厚度（配置范围 0.005 ~ 0.2）；NaN 按最保守处理（0）
     * @return &gt;= 0 的世界量上界
     */
    public static float maxOutlineWorld(float barThickness) {
        if (Float.isNaN(barThickness)) {
            return 0.0F;
        }
        return Math.max(0.0F, HALF_TILE - barThickness);
    }

    /**
     * 相邻条柱两侧同时外扩后的间隙（数值断言用）：{@code 1 − 2×(barThickness + widen)}。
     *
     * @param barThickness 条柱厚度
     * @param widenWorld   单侧外扩量（世界单位）
     * @return 间隙（格）；负数表示重叠
     */
    public static float neighbourGap(float barThickness, float widenWorld) {
        return 1.0F - 2.0F * (barThickness + widenWorld);
    }

    /**
     * 描边是否启用（默认档判据）。
     *
     * @param widthPx 描边宽度
     * @return true 仅当收敛后宽度 &gt; 0
     */
    public static boolean isOutlineEnabled(float widthPx) {
        return outlineWidthPx(widthPx) > 0.0F;
    }

    /**
     * 类别 → 调色板下标，与 {@code preview.vert} 的 {@code previewSemanticColor()} 逐条对应。
     *
     * <p>映射（接口冻结 §D 类别表，task-16 冻结）：
     * 0 → 主色；1 → 子模式色；2 → 远端色；3 → 截断色；4/5/255 及任何未知值 → 主色兜底。</p>
     *
     * <p><strong>未知值绝不返回非法下标</strong>——这是「不得出现空洞或异常色」的可断言形式。</p>
     *
     * @param semanticClass aAux.x 还原后的类别 id（0..255）
     * @return {@link #PALETTE_PRIMARY} / {@link #PALETTE_SECONDARY} /
     *         {@link #PALETTE_REMOTE} / {@link #PALETTE_TRUNCATED}
     */
    public static int paletteIndexFor(int semanticClass) {
        if (semanticClass == SEMANTIC_SUB_MODE_LOCAL) {
            return PALETTE_SECONDARY;
        }
        if (semanticClass == SEMANTIC_REMOTE_PREDICTED) {
            return PALETTE_REMOTE;
        }
        if (semanticClass == SEMANTIC_TRUNCATED) {
            return PALETTE_TRUNCATED;
        }
        return PALETTE_PRIMARY;
    }

    /**
     * 配置色（int RGB）→ 单通道 float：按 8bit 量化（{@code /255}）。
     *
     * <p>量化差异只出现在 {@code colorSource=config} 档；builtin 档传的是精确基线常量
     * (0.25, 0.9, 1.0)，逐位等于 legacy 颜色流。</p>
     *
     * @param rgb   0xRRGGBB 整数
     * @param shift 通道位移：16=R、8=G、0=B
     * @return 0..1 的通道值
     */
    public static float colorChannel(int rgb, int shift) {
        return ((rgb >>> shift) & 0xFF) / 255.0F;
    }

    /**
     * 浮点通道 → 0..255 整数（与 {@link #colorChannel(int, int)} 互逆）。
     *
     * @param channel 0..1 的通道值
     * @return 0..255
     */
    public static int quantizeChannel(float channel) {
        return (int) Math.floor(clamp(channel, 0.0F, 1.0F) * 255.0F + 0.5F);
    }

    /**
     * builtin 档调色板（浮点）：四类都是精确基线常量 (0.25, 0.9, 1.0)。
     *
     * <p><strong>静态复用不可变数组</strong>（F4）：本方法会在每帧 uniform 路径上被调用，
     * 不得每次新建数组；返回的数组<strong>只读</strong>，调用方不得修改。</p>
     *
     * <p><strong>刻意不经 8bit 量化往返</strong>：{@code round(0.9×255) = 230}、
     * {@code 230/255 = 0.9019608}，与 legacy 常量 0.9F 差 1.96e-3 —— 那正是「builtin 必须逐位
     * 等于现状」不允许的色差。量化只属于 config 档（配置本来就以 int RGB 存储）。</p>
     *
     * @return 长度 4 × 3 的 RGB（只读，禁止修改）
     */
    public static float[][] builtinColorTable() {
        return BUILTIN_COLOR_TABLE;
    }

    /**
     * legacy 基线常量色打包成 0xRRGGBB（仅供诊断与 int 口径断言使用）。
     *
     * <p>真实上传路径不做这条往返（见 {@link #builtinColorTable()}）。</p>
     *
     * @return 0xRRGGBB
     */
    public static int baselineColorRgb() {
        return (quantizeChannel(BUILTIN_COLOR_RED) << 16)
                | (quantizeChannel(BUILTIN_COLOR_GREEN) << 8)
                | quantizeChannel(BUILTIN_COLOR_BLUE);
    }

    /**
     * builtin 档调色板（int 口径，供 {@link #paletteFor} 的统一签名与断言使用）。
     *
     * <p>注意：int 口径本身携带 8bit 量化误差（0.9 → 230/255），<strong>不要</strong>用它做
     * builtin 档的逐位断言——逐位断言请用 {@link #builtinColorTable()}。</p>
     *
     * @return 长度 4 的 int 数组，下标与 {@link #PALETTE_PRIMARY} 等一致
     */
    public static int[] builtinPalette() {
        int rgb = baselineColorRgb();
        return new int[] {rgb, rgb, rgb, rgb};
    }

    /**
     * 选择调色板：{@code config} 档返回传入四色，其余（{@code builtin}/null/未知 id）
     * 一律返回 {@link #builtinPalette()}——未知档位绝不产生异常色。
     *
     * @param colorSourceId  颜色来源稳定 id
     * @param colorPrimary   配置主色 0xRRGGBB
     * @param colorSecondary 配置子模式色 0xRRGGBB
     * @param colorRemote    配置远端色 0xRRGGBB
     * @param colorTruncated 配置截断色 0xRRGGBB
     * @return 长度 4 的 int 调色板
     */
    public static int[] paletteFor(
            String colorSourceId, int colorPrimary, int colorSecondary, int colorRemote, int colorTruncated) {
        if (COLOR_SOURCE_CONFIG.equals(colorSourceId)) {
            return new int[] {colorPrimary, colorSecondary, colorRemote, colorTruncated};
        }
        return builtinPalette();
    }

    /**
     * 语义类别 → builtin 档最终输出 RGB（精确基线常量口径）。
     *
     * <p>builtin 档四色相同，故结果与类别无关；保留类别入参是为了让「类别选择链路」
     * 在测验里被真实走一遍（未知类别也必须返回基线常量，不得抛异常或返回零向量）。</p>
     *
     * @param semanticClass 类别 id（0..255；未知值同样兜底）
     * @return 长度为 3 的 RGB（精确基线常量）
     */
    public static float[] builtinColorRgb(int semanticClass) {
        // 真实链路：先做类别 → 调色板映射（未知值兜底主色），再取该槽位的精确浮点色。
        // 返回静态复用数组（只读），不做 clone —— 本方法在每帧路径上被调用（F4）。
        return BUILTIN_COLOR_TABLE[paletteIndexFor(semanticClass)];
    }

    /**
     * 语义类别 → 最终输出 RGB，逐条复刻 {@code preview.frag} 的选择逻辑。
     *
     * @param semanticClass  类别 id（0..255）
     * @param colorPrimary   主色 0xRRGGBB
     * @param colorSecondary 子模式色 0xRRGGBB
     * @param colorRemote    远端色 0xRRGGBB
     * @param colorTruncated 截断色 0xRRGGBB
     * @return 长度为 3 的 RGB（0..1）
     */
    public static float[] semanticColorRgb(
            int semanticClass, int colorPrimary, int colorSecondary, int colorRemote, int colorTruncated) {
        int palette = paletteIndexFor(semanticClass);
        int rgb;
        if (palette == PALETTE_SECONDARY) {
            rgb = colorSecondary;
        } else if (palette == PALETTE_REMOTE) {
            rgb = colorRemote;
        } else if (palette == PALETTE_TRUNCATED) {
            rgb = colorTruncated;
        } else {
            rgb = colorPrimary;
        }
        return new float[] {colorChannel(rgb, 16), colorChannel(rgb, 8), colorChannel(rgb, 0)};
    }

    /**
     * 屏幕最小宽度对应的横向放大倍数（GLSL {@code widen}）。
     *
     * <p>与 {@link #lateralClamp} 共享同一条数学：本方法回答「放大几倍」，
     * {@code lateralClamp} 回答「位移后的坐标」。两者都必须在 px&lt;=0 时恒等返回 1，
     * 否则「关闭效果 = 严格恒等」的契约会被破坏。</p>
     *
     * @param minScreenWidthPx    目标最小屏幕宽度（px）；&lt;= 0 表示关闭
     * @param lateralMagnitude    顶点横向偏移量（世界单位，= halfThickness）
     * @param pixelPerUnitAtDepth 单位深度上的像素/世界单位
     * @param lateralProjection   横向单位向量在相机空间的投影长度（0..1）
     * @return 放大倍数，恒 &gt;= 1
     */
    public static float lateralWiden(
            float minScreenWidthPx, float lateralMagnitude, float pixelPerUnitAtDepth, float lateralProjection) {
        if (!(minScreenWidthPx > 0.0F) || !(lateralMagnitude > 0.0F)) {
            return 1.0F;
        }
        float projectedPerUnit = clamp(lateralProjection, MIN_LATERAL_PROJECTION, 1.0F);
        float lateralWidthPx = 2.0F * lateralMagnitude * pixelPerUnitAtDepth * projectedPerUnit;
        if (!(lateralWidthPx > 0.0F) || !isFinite(lateralWidthPx)) {
            return 1.0F;
        }
        return clamp(minScreenWidthPx / lateralWidthPx, 1.0F, MAX_LATERAL_WIDEN);
    }

    /**
     * 屏幕最小宽度钳制：与 {@code preview.vert} 的横向放大逐式同形。
     *
     * @param minScreenWidthPx    目标最小屏幕宽度（px）；<= 0 表示关闭（严格恒等）
     * @param positionX           相对 meshOrigin 的 X
     * @param positionY           相对 meshOrigin 的 Y
     * @param positionZ           相对 meshOrigin 的 Z
     * @param pixelPerUnitAtDepth 单位深度上的像素/世界单位（= uPixelScale / depth）
     * @param lateralProjection   横向单位向量在相机空间的投影长度（0..1）
     * @param barThickness        条柱厚度（与 GLSL 的 uBarThickness 同源，用于退化判据）
     * @return 钳制后的局部坐标（长度 3）；px<=0 时逐值等于输入
     */
    public static float[] lateralClamp(
            float minScreenWidthPx,
            float positionX, float positionY, float positionZ,
            float pixelPerUnitAtDepth, float lateralProjection, float barThickness) {
        float[] original = {positionX, positionY, positionZ};
        if (!(minScreenWidthPx > 0.0F)) {
            return original;
        }

        float magnitudeX = Math.abs(positionX);
        float magnitudeY = Math.abs(positionY);
        float magnitudeZ = Math.abs(positionZ);
        float lateralMagnitude = Math.min(magnitudeX, Math.min(magnitudeY, magnitudeZ));
        // T13-D2：与 GLSL 同形的退化判据（preview.vert 的 lateralMagnitude <= 0.02 × thickness）。
        // 低于该量级的偏移不是「条柱半厚度」而是格线残留，放大它只会把顶点推出方块。
        if (lateralMagnitude <= 0.02F * Math.max(barThickness, 1e-4F)) {
            return original;
        }

        float axisX = 0.0F;
        float axisY = 0.0F;
        float axisZ = 0.0F;
        // 判据必须是「哪个轴的分量最小」。此前拿 lateralMagnitude（它本身就是三轴最小值）
        // 去和 magnitudeY/Z 比，条件恒真 ⇒ 位移永远沿 X 轴，远距时把条柱推出方块。
        if (magnitudeX <= magnitudeY && magnitudeX <= magnitudeZ) {
            axisX = sign(positionX);
        } else if (magnitudeY <= magnitudeZ) {
            axisY = sign(positionY);
        } else {
            axisZ = sign(positionZ);
        }

        float projectedPerUnit = clamp(lateralProjection, MIN_LATERAL_PROJECTION, 1.0F);
        float lateralWidthPx = 2.0F * lateralMagnitude * pixelPerUnitAtDepth * projectedPerUnit;
        float widen = clamp(minScreenWidthPx / Math.max(lateralWidthPx, 1e-6F), 1.0F, MAX_LATERAL_WIDEN);
        float delta = lateralMagnitude * (widen - 1.0F);
        return new float[] {positionX + axisX * delta, positionY + axisY * delta, positionZ + axisZ * delta};
    }

    /**
     * {@link #lateralClamp} 的便捷重载：使用 legacy 基线厚度 0.045（= ChainPreviewMeshBuilder 默认）。
     *
     * @param minScreenWidthPx    目标最小屏幕宽度（px）
     * @param positionX           相对 meshOrigin 的 X
     * @param positionY           相对 meshOrigin 的 Y
     * @param positionZ           相对 meshOrigin 的 Z
     * @param pixelPerUnitAtDepth 单位深度上的像素/世界单位
     * @param lateralProjection   横向单位向量在相机空间的投影长度
     * @return 钳制后的局部坐标
     */
    public static float[] lateralClamp(
            float minScreenWidthPx,
            float positionX, float positionY, float positionZ,
            float pixelPerUnitAtDepth, float lateralProjection) {
        return lateralClamp(minScreenWidthPx, positionX, positionY, positionZ,
                pixelPerUnitAtDepth, lateralProjection, DEFAULT_BAR_THICKNESS);
    }

    private static float sign(float value) {
        if (value > 0.0F) {
            return 1.0F;
        }
        return value < 0.0F ? -1.0F : 0.0F;
    }

    /**
     * 归一化字节属性还原：与 GLSL {@code floor(value * 255.0 + 0.5)} 同形。
     *
     * @param normalizedAttribute 0..1 的归一化分量（GL_UNSIGNED_BYTE normalized 读取值）
     * @return 0..255 的原始通道值
     */
    public static int unquantizeChannel(float normalizedAttribute) {
        return (int) Math.floor(clamp(normalizedAttribute, 0.0F, 1.0F) * 255.0F + 0.5F);
    }

    /** 语义类别是否应在片元着色器被丢弃（与 GLSL 的 discard 阈值一致）。 */
    public static boolean isFragmented(float alpha) {
        return alpha <= FRAGMENT_DISCARD_ALPHA;
    }

    /**
     * legacy 路径的混合源色：固定管线用顶点色流直接作为 {@code gl_Color}，共用混合为
     * {@code GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA}，故 src = 基色 × alpha。
     *
     * @param baseRed   顶点流基色 R
     * @param baseGreen 顶点流基色 G
     * @param baseBlue  顶点流基色 B
     * @param alpha     顶点 alpha
     * @return 混合源色 RGB
     */
    public static float[] legacyMixedSource(float baseRed, float baseGreen, float baseBlue, float alpha) {
        return new float[] {baseRed * alpha, baseGreen * alpha, baseBlue * alpha};
    }

    /**
     * shader 路径的混合源色：片元输出 {@code vec4(selectSemanticColor(), vColor.a)}，
     * 即颜色完全由语义色 uniform 决定，顶点色只提供 alpha。
     *
     * <p>两条不可违反的性质：</p>
     * <ol>
     *   <li><b>不乘 alpha 到 rgb</b>：共用 blend 是 {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}，
     *       预乘会退化成 {@code rgb × alpha²}（alpha=0.15 → 0.0225 vs 0.15）；</li>
     *   <li><b>不再乘顶点基色</b>：顶点流已是 (0.25, 0.9, 1.0)，再乘一次会得到
     *       (0.0625, 0.81, 1.0) —— R 掉到 1/4，肉眼可见偏暗偏蓝。</li>
     * </ol>
     * builtin 档语义色精确等于基线常量，故本函数结果与 {@link #legacyMixedSource} 逐位相等。
     *
     * @param semanticRed   语义色 R（builtin 档 = 0.25F）
     * @param semanticGreen 语义色 G（builtin 档 = 0.9F）
     * @param semanticBlue  语义色 B（builtin 档 = 1.0F）
     * @param alpha         顶点 alpha
     * @return 混合源色 RGB
     */
    public static float[] shaderMixedSource(
            float semanticRed, float semanticGreen, float semanticBlue, float alpha) {
        return new float[] {semanticRed * alpha, semanticGreen * alpha, semanticBlue * alpha};
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
