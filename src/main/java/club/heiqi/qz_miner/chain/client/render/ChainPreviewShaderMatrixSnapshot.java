package club.heiqi.qz_miner.chain.client.render;

import java.util.Locale;

/**
 * 相机矩阵诊断快照（T48c-C）：默认关闭，{@code -Dqz_miner.preview.diagnostics=true} 时在首次成功
 * 绘制打一行 INFO，之后永不再打。
 *
 * <p><b>为什么要有它</b>：真机若再出现「预览被画进错误空间」，这一行即包含离线复算所需的全部输入：
 * 投影 P(16)、modelview MV(16)、MVP(16)（各按 GL 列主序原样输出）、期望/实际平移列模长、
 * 相机 renderPos、mesh origin、indexCount/vertexCount，以及锚点顶点（网格顶点 0）的局部坐标、
 * CPU 端裁剪坐标与 NDC。有了它就能在桌面端回答「网格应该落在哪 vs 实际落在哪」，
 * 无需再加代码、无需再跑一轮真机。</p>
 *
 * <p>属性名与 {@code ChainPreviewRenderCache.DIAGNOSTICS_PROPERTY} <b>同源同名</b>
 * （{@code qz_miner.preview.diagnostics}）：后者是包私有常量，跨包无法直接引用，故此处独立声明，
 * <b>改名必须两处同步</b>。</p>
 *
 * <p>线程契约：只在渲染线程调用；格式化用 {@link Locale#ROOT}，不受宿主区域设置影响。</p>
 */
public final class ChainPreviewShaderMatrixSnapshot {

    /** 诊断开关属性名（与 {@code ChainPreviewRenderCache.DIAGNOSTICS_PROPERTY} 同源同名）。 */
    public static final String DIAGNOSTICS_PROPERTY = "qz_miner.preview.diagnostics";

    /** 快照行前缀（真机 grep 用）。 */
    public static final String PREFIX = "[ChainPreview][diag] matrixSnapshot{";

    private ChainPreviewShaderMatrixSnapshot() {
    }

    /**
     * 属性值 → 开关：仅 {@code "true"}（忽略大小写、允许首尾空白）为开，与
     * {@link Boolean#getBoolean(String)} 的语义一致。
     *
     * @param propertyValue 系统属性值，可为 null
     * @return 是否开启
     */
    public static boolean enabled(String propertyValue) {
        return propertyValue != null && "true".equalsIgnoreCase(propertyValue.trim());
    }

    /**
     * 本次会话是否请求输出快照：只读一次系统属性。
     *
     * <p>{@code -D} 是 JVM 启动参数、运行期不会变化，所以「只输出一次」由调用方的一次性标记负责
     * ——调用方在<b>首次绘制时无条件置位</b>该标记（含属性关闭的情形），因此属性总共只被读一次，
     * 之后每帧只剩一次布尔判断，默认关闭时零日志、零格式化开销。</p>
     *
     * @return 属性是否为 {@code true}
     */
    public static boolean requested() {
        return enabled(System.getProperty(DIAGNOSTICS_PROPERTY));
    }

    /**
     * 兼容入口（T48c-C 探针在用）：{@code !alreadyReported && requested()}。
     *
     * <p><b>删除条件</b>：{@code chain/client/verify/T48cC*.java} 迁移到 {@link #requested()} 之后即可删除
     * —— 产品路径已改为「首次绘制无条件锁存 + {@link #requested()}」，本方法不再被产品代码使用。</p>
     *
     * @param alreadyReported 本次会话是否已经输出过
     * @return 是否应输出
     */
    public static boolean shouldReport(boolean alreadyReported) {
        return !alreadyReported && requested();
    }

    /**
     * 兼容重载（T48c-C 探针在用）：不带视图朝向入参 ⇒ {@code viewRotation} 按 0/0 输出。
     *
     * <p><b>删除条件</b>：探针迁移到 13 参版本（含 {@code viewYaw}/{@code viewPitch}）后删除。</p>
     *
     * @see #format(float[], float[], float[], double, float, double[], float, float, int[], int, int, float[], float[])
     */
    public static String format(
            float[] projection, float[] modelView, float[] modelViewProjection,
            double expectedMagnitude, float actualMagnitude,
            double[] renderPos, int[] origin, int indexCount, int vertexCount,
            float[] anchorLocal, float[] anchorClip) {
        return format(projection, modelView, modelViewProjection, expectedMagnitude, actualMagnitude,
                renderPos, 0.0F, 0.0F, origin, indexCount, vertexCount, anchorLocal, anchorClip);
    }

    /**
     * 组装单行快照。
     *
     * <p>字段顺序固定（供离线脚本按位置解析）：projection → modelView → modelViewProjection →
     * expected → actual → renderPos → viewRotation → origin → indexCount → vertexCount →
     * anchorLocal → anchorClip → anchorNdc。</p>
     *
     * <p>{@code viewRotation} 取 {@code RenderManager.instance.playerViewY/playerViewX}
     * （视图实体插值后的 yaw/pitch；第三人称反向视角下 yaw 已 +180，见 vanilla {@code RenderManager}）。
     * 它是「离线复算朝向」的唯一输入：只有 P/MV 而没有朝向，无法判断一个合法但陈旧的旋转矩阵
     * 是「当时就该这样」还是「错了」。</p>
     *
     * <p>非法/缺失入参不抛：矩阵按零矩阵输出、元组按 0 输出（诊断路径不得影响渲染帧）。</p>
     *
     * @param projection           列主序投影矩阵（长度 16）
     * @param modelView            列主序 modelview（长度 16）
     * @param modelViewProjection  列主序 MVP（长度 16）
     * @param expectedMagnitude    期望平移列模长 {@code |origin − renderPos|}
     * @param actualMagnitude      实际平移列模长
     * @param renderPos            相机渲染位置 {x, y, z}
     * @param viewYaw              视图 yaw（{@code RenderManager.instance.playerViewY}）
     * @param viewPitch            视图 pitch（{@code RenderManager.instance.playerViewX}）
     * @param origin               mesh origin {x, y, z}（int）
     * @param indexCount           当前索引数
     * @param vertexCount          当前顶点数
     * @param anchorLocal          锚点局部坐标 {x, y, z}
     * @param anchorClip           锚点 CPU 裁剪坐标 {x, y, z, w}
     * @return 单行文本（{@link #PREFIX} 开头）
     */
    public static String format(
            float[] projection, float[] modelView, float[] modelViewProjection,
            double expectedMagnitude, float actualMagnitude,
            double[] renderPos, float viewYaw, float viewPitch,
            int[] origin, int indexCount, int vertexCount,
            float[] anchorLocal, float[] anchorClip) {
        StringBuilder text = new StringBuilder(512);
        text.append(PREFIX);
        appendMatrix(text, "projection", projection);
        text.append(", ");
        appendMatrix(text, "modelView", modelView);
        text.append(", ");
        appendMatrix(text, "modelViewProjection", modelViewProjection);
        text.append(", expected=").append(fixed(expectedMagnitude));
        text.append(", actual=").append(fixed(actualMagnitude));
        text.append(", renderPos=").append(tuple(renderPos));
        text.append(", viewRotation=(").append(fixed(viewYaw)).append(',').append(fixed(viewPitch)).append(')');
        text.append(", origin=").append(origin == null || origin.length < 3
                ? "(0,0,0)" : "(" + origin[0] + "," + origin[1] + "," + origin[2] + ")");
        text.append(", indexCount=").append(indexCount);
        text.append(", vertexCount=").append(vertexCount);
        text.append(", anchorLocal=").append(tuple(anchorLocal));
        text.append(", anchorClip=").append(tuple(anchorClip));
        text.append(", anchorNdc=").append(tuple(ndc(anchorClip)));
        text.append('}');
        return text.toString();
    }

    /** 裁剪坐标 → NDC（w=0 时按 NaN 输出，不做任何修补）。 */
    private static float[] ndc(float[] clip) {
        float[] out = new float[3];
        if (clip == null || clip.length < 4 || clip[3] == 0.0F) {
            out[0] = Float.NaN;
            out[1] = Float.NaN;
            out[2] = Float.NaN;
            return out;
        }
        out[0] = clip[0] / clip[3];
        out[1] = clip[1] / clip[3];
        out[2] = clip[2] / clip[3];
        return out;
    }

    private static void appendMatrix(StringBuilder text, String name, float[] matrix) {
        text.append(name).append('=').append('[');
        for (int index = 0; index < ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(matrix != null && index < matrix.length ? fixed(matrix[index]) : fixed(0.0F));
        }
        text.append(']');
    }

    private static String tuple(double[] values) {
        if (values == null || values.length < 3) {
            return "(0.000000,0.000000,0.000000)";
        }
        return "(" + fixed(values[0]) + "," + fixed(values[1]) + "," + fixed(values[2]) + ")";
    }

    private static String tuple(float[] values) {
        if (values == null || values.length < 3) {
            return "(0.000000,0.000000,0.000000)";
        }
        return "(" + fixed(values[0]) + "," + fixed(values[1]) + "," + fixed(values[2]) + ")";
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
