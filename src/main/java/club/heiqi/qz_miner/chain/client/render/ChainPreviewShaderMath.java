package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;

/**
 * preview.vert/preview.frag 纯计算的 CPU 参考模型，供离线数值互证及生产 uniform 归一化复用。
 * 距离曲线与 VisualParameters.alphaFor 逐点对照，GL 编译、像素和状态恢复另经实机验证。
 */
public final class ChainPreviewShaderMath {

    /** 淡出曲线的分母下限，与 GLSL 的 {@code max(uFadeEnd - uFadeStart, 1e-4)} 一致。 */
    private static final float FADE_SPAN_EPSILON = 1e-4F;

    /** 像素 / 世界单位的下限，与 GLSL 的 {@code max(..., 1e-6)} 一致。 */
    private static final float PIXELS_PER_UNIT_EPSILON = 1e-6F;

    /** 深度下限，与 GLSL 的 {@code max(1e-4, depth)} 一致。 */
    private static final float DEPTH_EPSILON = 1e-4F;

    /** 片元丢弃阈值，与 GLSL 的 {@code vColor.a <= 0.0039} 一致。 */
    private static final float FRAGMENT_DISCARD_ALPHA = 0.0039F;

    /** legacy 基线条柱厚度（= ChainPreviewMeshBuilder 的默认值，供便捷重载使用）。 */
    private static final float DEFAULT_BAR_THICKNESS = 0.045F;

    /** appearOrder 未定义哨兵（顶点属性契约（真源：preview.vert 头部属性段）：0xFFFF）。 */
    public static final float APPEAR_ORDER_UNDEFINED = 65535.0F;

    /** legacy 基线常量色 R（= ChainPreviewMeshBuilder.BASE_RED）。 */
    public static final float BUILTIN_COLOR_RED = 0.25F;
    /** legacy 基线常量色 G（= ChainPreviewMeshBuilder.BASE_GREEN）。 */
    public static final float BUILTIN_COLOR_GREEN = 0.9F;
    /** legacy 基线常量色 B（= ChainPreviewMeshBuilder.BASE_BLUE）。 */
    public static final float BUILTIN_COLOR_BLUE = 1.0F;

    /** 六槽静态只读调色板；CHAIN 保留 legacy 精确 float 基色，其余按 RGB 常量换算，供每帧复用。 */
    private static final float[][] BUILTIN_COLOR_TABLE = {
        {BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE},
        rgbTriple(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_AREA_RGB),
        rgbTriple(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_INTERACT_RGB),
        rgbTriple(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_SUB_MODE_RGB),
        rgbTriple(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_REMOTE_RGB),
        rgbTriple(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_TRUNCATED_RGB),
    };

    /** 颜色来源稳定 id：内置（legacy 现状兼容色）。 */
    public static final String COLOR_SOURCE_BUILTIN = "builtin";
    /** 颜色来源稳定 id：配置（六色来自 clientPreviewColor*）。 */
    public static final String COLOR_SOURCE_CONFIG = "config";

    // ---------------------------------------------------------------- 语义类别表（真源：ChainPreviewSemanticClass） 语义类别（冻结）

    // Java 语义 id 引用 ChainPreviewSemanticClass，GLSL 保留对应字面量与来源引用。
    /** 0 CHAIN_LOCAL CHAIN 默认子模式本地预测 → CHAIN 色。 */
    public static final int SEMANTIC_CHAIN_LOCAL = ChainPreviewSemanticClass.CHAIN_LOCAL;
    /** 1 AREA_LOCAL AREA 默认子模式本地预测 → AREA 色。 */
    public static final int SEMANTIC_AREA_LOCAL = ChainPreviewSemanticClass.AREA_LOCAL;
    /** 2 INTERACT_LOCAL INTERACT 默认子模式本地预测 → INTERACT 色。 */
    public static final int SEMANTIC_INTERACT_LOCAL = ChainPreviewSemanticClass.INTERACT_LOCAL;
    /** 3 SUB_MODE_LOCAL 扩展子模式本地预测 → 扩展子模式色。 */
    public static final int SEMANTIC_SUB_MODE_LOCAL = ChainPreviewSemanticClass.SUB_MODE_LOCAL;
    /** 4 REMOTE_PREDICTED 远端预测 → 远端色。 */
    public static final int SEMANTIC_REMOTE_PREDICTED = ChainPreviewSemanticClass.REMOTE_PREDICTED;
    /** 5 TRUNCATED 截断（本轮数据源不产出，保留合法分支）→ 截断色。 */
    public static final int SEMANTIC_TRUNCATED = ChainPreviewSemanticClass.TRUNCATED;
    /** 6 DEFERRED 待执行（未启用）→ CHAIN 色兜底。 */
    public static final int SEMANTIC_DEFERRED = ChainPreviewSemanticClass.DEFERRED;
    /** 7 EXECUTED 已执行（未启用）→ CHAIN 色兜底。 */
    public static final int SEMANTIC_EXECUTED = ChainPreviewSemanticClass.EXECUTED;
    /** 255 UNDEFINED 未定义 → CHAIN 色兜底。 */
    public static final int SEMANTIC_UNDEFINED = ChainPreviewSemanticClass.UNDEFINED;

    /**
     * 调色板槽位数：与 {@code preview.vert} 的 uColor* uniform 条数、
     * {@link ChainPreviewDrawPlan.Visuals.Colors} 的色槽字段数三处同源。
     */
    public static final int PALETTE_SLOT_COUNT = 6;

    /** 调色板下标：CHAIN 大模式色（同时是未知类别兜底色与描边色）。 */
    public static final int PALETTE_CHAIN = 0;
    /** 调色板下标：AREA 大模式色。 */
    public static final int PALETTE_AREA = 1;
    /** 调色板下标：INTERACT 大模式色。 */
    public static final int PALETTE_INTERACT = 2;
    /** 调色板下标：扩展子模式色。 */
    public static final int PALETTE_SECONDARY = 3;
    /** 调色板下标：远端色。 */
    public static final int PALETTE_REMOTE = 4;
    /** 调色板下标：截断色。 */
    public static final int PALETTE_TRUNCATED = 5;

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

    /** 连锁序 rgb 权重的恒等下限。 */
    public static final float ORDER_MIN_BRIGHTNESS_OFF = 1.0F;

    /**
     * 连锁序**亮度**权重下限的 host 侧收敛（与 {@code uOrderMinBrightness} 的写入边界同口径）。
     *
     * <p>{@code 1.0} 是恒等值：{@code preview.vert} 在 {@code uOrderMinBrightness >= 1.0} 时完全不进入
     * orderWeight 分支。NaN / Infinity 收敛为恒等而不是传播——与
     * {@link #outlineWidthPx(float)} 的「非法即关闭」同取向：异常配置不得把远处条柱的亮度压成纯黑。</p>
     *
     * @param requested 配置 / plan 给出的下限（可为 NaN / 越界）
     * @return [0,1] 的下限；非法输入为 {@link #ORDER_MIN_BRIGHTNESS_OFF}
     */
    public static float orderMinBrightness(float requested) {
        if (!isFinite(requested)) {
            return ORDER_MIN_BRIGHTNESS_OFF;
        }
        return clamp(requested, 0.0F, 1.0F);
    }

    /** 内部结构 rgb 亮度的恒等系数。 */
    public static final float INTERIOR_DIM_OFF = 1.0F;

    /**
     * 「内部结构」判据的 aAux.y 下界，即 {@code preview.vert} 的 {@code auxChannel(aAux.y) >= 4.0}。
     *
     * <p>{@code aAux.y} 是 tubeEdge（{@link ChainPreviewMesh#AUX_BYTES_PER_VERTEX}）：0..3 = 贯通管面
     * 槽位，{@code 255} = {@link ChainPreviewMesh#AUX_UNDEFINED}（junction 补块 / 共享顶点）。
     * 判据刻意写成 {@code >= 4.0} 而不是 {@code == 255.0}：它同时覆盖「槽位 2/3 将来可达」
     * 与「未知大值」两种情形——除了 0..3 这些<b>贯通面</b>以外的一切顶点都算内部结构。
     * 反过来把 0..3 误判成内部会让整根条柱侧面被压暗，与「外轮廓强」直接矛盾。</p>
     *
     * <p><b>本常量不参与任何 host 侧计算</b>：{@code uInteriorDim} 是整条 draw call 的统一乘数，
     * 逐顶点的 tubeEdge 判断只发生在顶点阶段（GLSL 侧有独立字面量，无法共享 Java 常量，改动须两处同步）。
     * 放在这里是为了给「阈值 4.0 从哪来」一个可被 javadoc 引用的落点。</p>
     */
    public static final float INTERIOR_TUBEEDGE_THRESHOLD = 4.0F;

    /** 将内部格线 rgb 亮度系数收窄到 [0,1]，非有限输入回退恒等值，供 host 上传复用。 */
    public static float interiorDim(float requested) {
        if (!isFinite(requested)) {
            return INTERIOR_DIM_OFF;
        }
        return clamp(requested, 0.0F, 1.0F);
    }

    /**
     * 将有效 appearOrder 按目标总数归一化，返回从恒等值递减到配置下限的 rgb 权重。
     * floor 固定序号格，与生长边界一致；未定义序号、非法总数或恒等下限返回 1。
     *
     * @param orderMinBrightness 权重下限，非法值回退恒等亮度
     * @param appearOrder 已还原的 u16 序号，未定义值为 65535
     * @param appearSpan 真实目标总数，与 shader uAppearSpan 同源
     * @return 用于 rgb 乘色的 [0,1] 权重
     */
    public static float orderWeight(float orderMinBrightness, float appearOrder, float appearSpan) {
        if (!(orderMinBrightness < ORDER_MIN_BRIGHTNESS_OFF) || !(appearSpan > 0.0F)) {
            return 1.0F;
        }
        if (!(appearOrder < APPEAR_ORDER_UNDEFINED)) {
            return 1.0F;
        }
        float normalized = (float) Math.floor(Math.min(appearOrder, appearSpan)) / Math.max(appearSpan, 1.0F);
        float t = clamp(normalized, 0.0F, 1.0F);
        return 1.0F - (1.0F - orderMinBrightness) * t;
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
     * <p><b>本链路刻意不含连锁序权重</b>（{@link #orderWeight}）：该权重乘在颜色亮度上，
     * 距离淡出与连锁序是两个独立维度、各自权威（用户裁定）。往 {@code vColor.a} 里再补一个
     * 「越远越淡」的乘数会把两个配置的语义重新绑到一个量上。</p>
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

    /** 半格：相邻条柱中心距 1 格，任一侧占用不得超过它（沿面法线的外扩受同一约束）。 */
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
     * <p><strong>本方法只看描边自己</strong>（最小宽度关闭时的口径）。两项同时激活时必须改用
     * {@link #outlineWidenWithinBudget(float, float, float, float)}：那条路径会再扣掉最小宽度
     * 已占用的预算，使联合位移不越出 {@link #maxWidenWorld(float)}。</p>
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
        return Math.min(px / pixelsPerWorldUnit, maxWidenWorld(barThickness));
    }

    /**
     * 便捷重载：按 legacy 默认厚度 {@value #DEFAULT_BAR_THICKNESS} 取世界上界。
     *
     * <p><strong>厚度相关断言必须用三参版本</strong>——本重载把上界固定成默认厚度对应的
     * {@code 0.455}，无法覆盖 {@code barThickness} 变化（例如 0.2 ⇒ 0.3、&ge;0.5 ⇒ 0）。
     * 保留它只为兼容既有调用点。</p>
     *
     * <p>上界口径见 {@link #maxWidenWorld(float)}（最小宽度与描边共用）。</p>
     *
     * @param widthPx            描边宽度（物理像素）
     * @param pixelsPerWorldUnit 横向「像素 / 世界单位」
     * @return 外扩的世界量
     */
    public static float outlineWidenWorld(float widthPx, float pixelsPerWorldUnit) {
        return outlineWidenWorld(widthPx, pixelsPerWorldUnit, DEFAULT_BAR_THICKNESS);
    }

    /**
     * 描边在「最小宽度已占用预算」之后的实际外扩量（联合上界，T52）。
     *
     * <p>与 {@code preview.vert} 的消耗式预算逐式同形：</p>
     * <pre>
     *   remaining = max(0, maxWidenWorld(t) − minWidthWiden)
     *   outline   = min(widthPx / ppwu, remaining)
     * </pre>
     *
     * <p><strong>为什么让描边让位</strong>（Lead 裁定）：最小宽度是功能性需求（远距条柱必须可见），
     * 真描边是装饰性的；两者争同一份世界空间预算时功能优先。{@code minWidthWiden} 吃满
     * {@link #maxWidenWorld(float)} 时本方法<b>精确返回 0</b>（而不是让描边把联合位移顶到
     * {@code 2×(0.5 − t)} 越出自身方块）。</p>
     *
     * @param widthPx             描边宽度（物理像素）；&lt;= 0 视为关闭
     * @param pixelsPerWorldUnit  像素 / 世界单位
     * @param barThickness        条柱厚度
     * @param consumedWorld       最小宽度已占用的位移量（{@link #minWidthWidenWorld} 的结果；
     *                            NaN / 负数按 0 计）
     * @return 描边的世界外扩量；关闭或预算耗尽时精确为 0
     */
    public static float outlineWidenWithinBudget(
            float widthPx, float pixelsPerWorldUnit, float barThickness, float consumedWorld) {
        float consumed = Float.isNaN(consumedWorld) ? 0.0F : Math.max(0.0F, consumedWorld);
        float remaining = Math.max(0.0F, maxWidenWorld(barThickness) - consumed);
        return Math.min(outlineWidenWorld(widthPx, pixelsPerWorldUnit, barThickness), remaining);
    }

    /** 按条柱世界厚度计算两项外扩能力共用的上界，最小宽度优先、描边消耗剩余预算。 */
    public static float maxWidenWorld(float barThickness) {
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
     * <p>映射（语义类别表（真源：ChainPreviewSemanticClass），本轮按大模式重排）：
     * 0/1/2 → CHAIN/AREA/INTERACT 色；3 → 扩展子模式色；4 → 远端色；5 → 截断色；
     * 6/7/255 及任何未知值 → CHAIN 色兜底。</p>
     *
     * <p><strong>未知值绝不返回非法下标</strong>——这是「不得出现空洞或异常色」的可断言形式。</p>
     *
     * @param semanticClass aAux.x 还原后的类别 id（0..255）
     * @return {@link #PALETTE_CHAIN} / {@link #PALETTE_AREA} / {@link #PALETTE_INTERACT} /
     *         {@link #PALETTE_SECONDARY} / {@link #PALETTE_REMOTE} / {@link #PALETTE_TRUNCATED}
     */
    public static int paletteIndexFor(int semanticClass) {
        switch (semanticClass) {
            case SEMANTIC_AREA_LOCAL:
                return PALETTE_AREA;
            case SEMANTIC_INTERACT_LOCAL:
                return PALETTE_INTERACT;
            case SEMANTIC_SUB_MODE_LOCAL:
                return PALETTE_SECONDARY;
            case SEMANTIC_REMOTE_PREDICTED:
                return PALETTE_REMOTE;
            case SEMANTIC_TRUNCATED:
                return PALETTE_TRUNCATED;
            default:
                return PALETTE_CHAIN;
        }
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
     * builtin 档调色板（浮点）：六槽按大模式可区分，CHAIN 槽是精确基线常量 (0.25, 0.9, 1.0)。
     *
     * <p><strong>静态复用不可变数组</strong>（F4）：本方法会在每帧 uniform 路径上被调用，
     * 不得每次新建数组；返回的数组<strong>只读</strong>，调用方不得修改。</p>
     *
     * <p><strong>CHAIN 槽刻意不经 8bit 量化往返</strong>：{@code round(0.9×255) = 230}、
     * {@code 230/255 = 0.9019608}，与 legacy 常量 0.9F 差 1.96e-3 —— 那正是「默认档必须逐位
     * 等于现状」不允许的色差。其余五槽是本轮新增色，只以 0xRRGGBB 声明，按 /255 换算
     * （与 config 档同一规则）。</p>
     *
     * @return 长度 {@link #PALETTE_SLOT_COUNT} × 3 的 RGB（只读，禁止修改）
     */
    public static float[][] builtinColorTable() {
        return BUILTIN_COLOR_TABLE;
    }

    /**
     * builtin 档调色板（int 口径，供 {@link #paletteFor} 的统一签名与断言使用）。
     *
     * <p>取值与 {@link ChainPreviewDrawPlan.Visuals.Colors} 的 {@code BUILTIN_*_RGB} 同源。
     * 注意：int 口径的 CHAIN 槽携带 8bit 量化误差（0.9 → 230/255），<strong>不要</strong>用它做
     * builtin 档的逐位断言——逐位断言请用 {@link #builtinColorTable()}。</p>
     *
     * @return 长度 {@link #PALETTE_SLOT_COUNT} 的 int 数组，下标与 {@link #PALETTE_CHAIN} 等一致
     */
    public static int[] builtinPalette() {
        return new int[] {
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_AREA_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_INTERACT_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_SUB_MODE_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_REMOTE_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_TRUNCATED_RGB,
        };
    }

    /**
     * 选择调色板：{@code config} 档返回传入六色，其余（{@code builtin}/null/未知 id）
     * 一律返回 {@link #builtinPalette()}——未知档位绝不产生异常色。
     *
     * @param colorSourceId  颜色来源稳定 id
     * @param colorChain     配置 CHAIN 大模式色 0xRRGGBB
     * @param colorArea      配置 AREA 大模式色 0xRRGGBB
     * @param colorInteract  配置 INTERACT 大模式色 0xRRGGBB
     * @param colorSecondary 配置扩展子模式色 0xRRGGBB
     * @param colorRemote    配置远端色 0xRRGGBB
     * @param colorTruncated 配置截断色 0xRRGGBB
     * @return 长度 {@link #PALETTE_SLOT_COUNT} 的 int 调色板
     */
    public static int[] paletteFor(
            String colorSourceId, int colorChain, int colorArea, int colorInteract,
            int colorSecondary, int colorRemote, int colorTruncated) {
        if (COLOR_SOURCE_CONFIG.equals(colorSourceId)) {
            return new int[] {colorChain, colorArea, colorInteract, colorSecondary, colorRemote, colorTruncated};
        }
        return builtinPalette();
    }

    /**
     * 语义类别 → builtin 档最终输出 RGB（静态精确表口径）。
     *
     * <p>六槽按大模式区分；保留类别入参是为了让「类别选择链路」在测验里被真实走一遍
     * （未知类别必须兜底 CHAIN 槽，不得抛异常或返回零向量）。</p>
     *
     * @param semanticClass 类别 id（0..255；未知值同样兜底）
     * @return 长度为 3 的 RGB（只读静态数组元素）
     */
    public static float[] builtinColorRgb(int semanticClass) {
        // 真实链路：先做类别 → 调色板映射（未知值兜底 CHAIN 色），再取该槽位的精确浮点色。
        // 返回静态复用数组（只读），不做 clone —— 本方法在每帧路径上被调用（F4）。
        return BUILTIN_COLOR_TABLE[paletteIndexFor(semanticClass)];
    }

    /**
     * 语义类别 → 最终输出 RGB，逐条复刻 {@code preview.vert} 的 {@code previewSemanticColor()}。
     *
     * @param semanticClass  类别 id（0..255）
     * @param colorChain     CHAIN 大模式色 0xRRGGBB
     * @param colorArea      AREA 大模式色 0xRRGGBB
     * @param colorInteract  INTERACT 大模式色 0xRRGGBB
     * @param colorSecondary 扩展子模式色 0xRRGGBB
     * @param colorRemote    远端色 0xRRGGBB
     * @param colorTruncated 截断色 0xRRGGBB
     * @return 长度为 3 的 RGB（0..1）
     */
    public static float[] semanticColorRgb(
            int semanticClass, int colorChain, int colorArea, int colorInteract,
            int colorSecondary, int colorRemote, int colorTruncated) {
        int rgb = colorForSlot(
            paletteIndexFor(semanticClass), colorChain, colorArea, colorInteract,
            colorSecondary, colorRemote, colorTruncated);
        return new float[] {colorChannel(rgb, 16), colorChannel(rgb, 8), colorChannel(rgb, 0)};
    }

    /**
     * 调色板槽位 → 0xRRGGBB（与 {@link #paletteIndexFor} 的分派一一对应，非法槽位兜底 CHAIN 色）。
     *
     * @param paletteSlot    槽位下标
     * @param colorChain     CHAIN 色
     * @param colorArea      AREA 色
     * @param colorInteract  INTERACT 色
     * @param colorSecondary 扩展子模式色
     * @param colorRemote    远端色
     * @param colorTruncated 截断色
     * @return 该槽位的 0xRRGGBB
     */
    private static int colorForSlot(
            int paletteSlot, int colorChain, int colorArea, int colorInteract,
            int colorSecondary, int colorRemote, int colorTruncated) {
        switch (paletteSlot) {
            case PALETTE_AREA:
                return colorArea;
            case PALETTE_INTERACT:
                return colorInteract;
            case PALETTE_SECONDARY:
                return colorSecondary;
            case PALETTE_REMOTE:
                return colorRemote;
            case PALETTE_TRUNCATED:
                return colorTruncated;
            default:
                return colorChain;
        }
    }

    /** @param rgb 0xRRGGBB @return 逐通道按 /255 换算的 RGB（供静态调色板声明使用） */
    private static float[] rgbTriple(int rgb) {
        return new float[] {colorChannel(rgb, 16), colorChannel(rgb, 8), colorChannel(rgb, 0)};
    }

    /**
     * 像素 / 世界单位换算：与 {@code preview.vert} 的
     * {@code max(uPixelScale / max(1e-4, -(uModelView * vec4(aPos, 1.0)).z), 1e-6)} 同形。
     *
     * @param pixelScale uPixelScale（单位深度上的像素 / 世界单位）
     * @param depth      顶点在相机空间的深度（{@code -(uModelView * vec4(aPos, 1)).z}）
     * @return 该顶点处的像素 / 世界单位，恒 &gt;= 1e-6
     */
    public static float pixelsPerWorldUnit(float pixelScale, float depth) {
        return Math.max(pixelScale / Math.max(DEPTH_EPSILON, depth), PIXELS_PER_UNIT_EPSILON);
    }

    /**
     * 屏幕最小宽度：沿面法线的世界位移量（与 {@code preview.vert} 逐式同形）。
     *
     * <p>GLSL 侧：{@code min(0.5 * uBarThickness * max(0.0, uMinScreenWidthPx
     * / max(uBarThickness * pixelsPerWorldUnit, 1e-6) - 1.0), maxWidenWorld)}。</p>
     *
     * <p><b>为什么基准是「总厚度 × 像素密度」</b>：条柱的屏幕投影宽就是
     * {@code barThickness × pixelsPerWorldUnit}（厚度是总厚，不是半厚）。本式把投影宽等比放大到
     * {@code minScreenWidthPx}（倍数 {@code k}），半厚 {@code t/2} 随之移到 {@code (t/2) × k}，
     * 故单侧位移 {@code (t/2)(k − 1)}，两侧合计正好补齐到目标像素宽。曾经写成
     * {@code 2.0 * uBarThickness} 的版本会让激活阈值翻倍、位移减半（配置 8px 只交付 4px），
     * 已由 Python 独立复算证伪（见工作站 temp/qz-miner-minwidth-a1a2-recheck.py）。</p>
     *
     * <p><b>世界上界</b>：与真描边同口径（{@link #maxWidenWorld(float)}）。像素 / 世界单位很小时
     * 位移必须收敛——无上界时 {@code ppwu=0.01, t=0.045, minW=8} 会单侧外扩约 400 格。</p>
     *
     * @param minScreenWidthPx   目标最小屏幕宽度（像素）；&lt;= 0 表示关闭（精确返回 0）
     * @param barThickness       条柱厚度（总厚，GLSL {@code uBarThickness}）
     * @param pixelsPerWorldUnit 像素 / 世界单位
     * @return 沿面法线的单侧位移量（世界单位）；关闭或输入非法时为 0
     */
    public static float minWidthWidenWorld(
            float minScreenWidthPx, float barThickness, float pixelsPerWorldUnit) {
        if (!(minScreenWidthPx > 0.0F) || !(barThickness > 0.0F) || !isFinite(barThickness)) {
            return 0.0F;
        }
        if (!(pixelsPerWorldUnit > 0.0F) || !isFinite(pixelsPerWorldUnit)) {
            return 0.0F;
        }
        float widthPx = Math.max(barThickness * pixelsPerWorldUnit, PIXELS_PER_UNIT_EPSILON);
        float widen = Math.max(0.0F, minScreenWidthPx / widthPx - 1.0F);
        return Math.min(0.5F * barThickness * widen, maxWidenWorld(barThickness));
    }

    /**
     * 按显式面法线计算最小宽度与描边的共享预算位移，返回 object-space 坐标。
     * 最小宽度优先，零方向保留原位置，覆盖 junction、连接点及方向退化路径。
     * 位置仅提供投影输入，方向归显式属性所有。
     */
    public static float[] displaceVertex(
            float positionX, float positionY, float positionZ,
            float directionX, float directionY, float directionZ,
            float pixelsPerWorldUnit, float minScreenWidthPx, float barThickness, float outlineWidthPx) {
        float[] displaced = {positionX, positionY, positionZ};
        // 与 GLSL 同序：最小宽度位移无条件写成（关闭/非法时为 0 ⇒ 逐值恒等，NaN 输入同样收敛）。
        float minWidthWiden = minWidthWidenWorld(minScreenWidthPx, barThickness, pixelsPerWorldUnit);
        displaced[0] = positionX + directionX * minWidthWiden;
        displaced[1] = positionY + directionY * minWidthWiden;
        displaced[2] = positionZ + directionZ * minWidthWiden;
        // 描边只吃剩余预算（GLSL 的 if (uOutlineWidthPx > 0.0) 门控已折进「关闭即 0」）。
        float outlineWiden = outlineWidenWithinBudget(
                outlineWidthPx, pixelsPerWorldUnit, barThickness, minWidthWiden);
        displaced[0] = displaced[0] + directionX * outlineWiden;
        displaced[1] = displaced[1] + directionY * outlineWiden;
        displaced[2] = displaced[2] + directionZ * outlineWiden;
        return displaced;
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
