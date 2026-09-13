package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;

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

    /** legacy 基线常量色 R（= ChainPreviewMeshBuilder.BASE_RED）。 */
    public static final float BUILTIN_COLOR_RED = 0.25F;
    /** legacy 基线常量色 G（= ChainPreviewMeshBuilder.BASE_GREEN）。 */
    public static final float BUILTIN_COLOR_GREEN = 0.9F;
    /** legacy 基线常量色 B（= ChainPreviewMeshBuilder.BASE_BLUE）。 */
    public static final float BUILTIN_COLOR_BLUE = 1.0F;

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
