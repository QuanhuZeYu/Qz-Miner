package club.heiqi.qz_miner.chain.client.render;

/**
 * 预览相机矩阵的纯函数参考实现（零 GL、零分配、可在 headless 内逐值断言）。
 *
 * <p><strong>为什么需要这个类（T48c-A）</strong>：着色器路径不再依赖固定管线内建
 * {@code gl_ModelViewProjectionMatrix} / {@code gl_ModelViewMatrix}。真机（GTNH 2.9 + Angelica
 * 2.2.10 的 GLSM 用生成着色器模拟固定管线 + {@code use_no_error_g_l_context=true}）下，这两个
 * 内建量与真实相机矩阵失同步，整条预览链被画进错误空间（77px 窄竖条），且失败完全不可观测。
 * 矩阵改由 CPU 侧读取（{@code glGetFloatv}）并相乘上传后，「乘法是否正确」与「读到的矩阵是否
 * 可信」就都成了纯 JVM 内可断言的问题，而不是只能靠真机观感判断。</p>
 *
 * <p><strong>列主序约定</strong>（GL 唯一约定，也是最易错处）：{@code m[column * 4 + row]}。
 * 平移落在<b>列 3</b>，即下标 {@link #TRANSLATION_COLUMN_X} = 12、13、14；
 * 行主序取的 m[3]/m[7]/m[11] 是投影行，不是平移（{@code ChainPreviewShaderMatrixMathTest}
 * 用反例把这条约定锁死）。</p>
 *
 * <p><strong>自检不变量</strong>（{@link #translationMatches}）：{@code ChainPreviewRenderer}
 * 在 draw 之前对栈做 {@code glTranslated(origin − renderPos)}，而世界坐标系在 CPU 侧已按
 * {@code renderPos} 平移（同一约定见 {@code CuboidSelectionRenderer}），故 draw 时刻的
 * modelview 平移列 = {@code R × (origin − renderPos)}；旋转不改变模长 ⇒
 * {@code |平移列| ≈ |origin − renderPos|}。矩阵栈若未被驱动更新（保持单位阵），该模长为 0，
 * 与期望值（通常 0.5~30 格）可区分——这正是真机表型的可检测形式。</p>
 *
 * <p>线程契约：与其余预览渲染代码一致，只在渲染线程调用。</p>
 */
public final class ChainPreviewShaderMatrixMath {

    /** 4×4 列主序矩阵元素个数（GL uniform 上传长度）。 */
    public static final int MATRIX_ELEMENTS = 16;

    /** 列主序平移列 X 分量下标。 */
    public static final int TRANSLATION_COLUMN_X = 12;
    /** 列主序平移列 Y 分量下标。 */
    public static final int TRANSLATION_COLUMN_Y = 13;
    /** 列主序平移列 Z 分量下标。 */
    public static final int TRANSLATION_COLUMN_Z = 14;

    /**
     * 自检下界系数：{@code |平移列| >= 0.5 × 期望}。
     *
     * <p><b>这一条是检测力的来源</b>：矩阵栈未更新时该模长为 0（或与期望相差一个数量级），
     * 必然落在下界之外。取 0.5 而不是 1.0 是为了容忍浮点累积与相机侧的小幅平移（bob）。</p>
     */
    public static final float TRANSLATION_LOWER_FACTOR = 0.5F;

    /** 自检上界系数：{@code |平移列| <= 2 × 期望 + 绝对松弛}。 */
    public static final float TRANSLATION_UPPER_FACTOR = 2.0F;

    /**
     * 自检绝对松弛（格）。
     *
     * <p>为什么必须给到 6 格：modelview 的平移列里除了 {@code origin − renderPos}，还可能叠着
     * 原版相机自身的平移——第三人称视角下拉回距离可达 4 格（1.7.10 {@code orientCamera} 的
     * 相机碰撞回退上限），第一人称行走时还有幅值 ≤0.5 格的 bob 与 float32 累积误差。
     * 绝对松弛取 4.0（第三人称上限）+ 2.0（余量）才不会在合法视角下误判（误判代价是白白
     * 回退 legacy）。上界只防「完全离谱的矩阵」，真正承担检测职责的是下界。</p>
     */
    public static final float TRANSLATION_ABSOLUTE_SLACK = 6.0F;

    /**
     * 期望距离的下限（格）：低于它时「平移列 ≈ 期望」与「平移列 = 0」不可区分
     * （例如 origin 与相机重合），此时放行不阻断——宁可放过也不误杀。
     */
    public static final float TRANSLATION_MIN_EXPECTED = 1.0e-3F;

    private ChainPreviewShaderMatrixMath() {}

    /**
     * 列主序 4×4 相乘：{@code out = left × right}（即先施加 right，再施加 left，与 GL 固定管线
     * 的 {@code gl_ModelViewProjectionMatrix = projection × modelview} 同序）。
     *
     * <p>展开成 16 条表达式并在写回 {@code out} 之前先算完所有元素，因此
     * 允许 {@code out} 与 {@code left}/{@code right} 指向同一数组，且不产生任何临时数组
     * （每帧路径，零分配）。</p>
     *
     * <p>非法输入（null 或长度 &lt; 16）静默返回且<b>不改写</b> {@code out}：调用方只在成功
     * 读取到矩阵后才调用，渲染帧不得因参数问题抛出或写出半成品矩阵。</p>
     *
     * @param out   目标（列主序，长度 &ge; 16）
     * @param left  左乘数（列主序，长度 &ge; 16）
     * @param right 右乘数（列主序，长度 &ge; 16）
     */
    public static void multiply4x4(float[] out, float[] left, float[] right) {
        if (!isUsable(out) || !isUsable(left) || !isUsable(right)) {
            return;
        }
        float r0 = left[0] * right[0] + left[4] * right[1] + left[8] * right[2] + left[12] * right[3];
        float r1 = left[1] * right[0] + left[5] * right[1] + left[9] * right[2] + left[13] * right[3];
        float r2 = left[2] * right[0] + left[6] * right[1] + left[10] * right[2] + left[14] * right[3];
        float r3 = left[3] * right[0] + left[7] * right[1] + left[11] * right[2] + left[15] * right[3];
        float r4 = left[0] * right[4] + left[4] * right[5] + left[8] * right[6] + left[12] * right[7];
        float r5 = left[1] * right[4] + left[5] * right[5] + left[9] * right[6] + left[13] * right[7];
        float r6 = left[2] * right[4] + left[6] * right[5] + left[10] * right[6] + left[14] * right[7];
        float r7 = left[3] * right[4] + left[7] * right[5] + left[11] * right[6] + left[15] * right[7];
        float r8 = left[0] * right[8] + left[4] * right[9] + left[8] * right[10] + left[12] * right[11];
        float r9 = left[1] * right[8] + left[5] * right[9] + left[9] * right[10] + left[13] * right[11];
        float r10 = left[2] * right[8] + left[6] * right[9] + left[10] * right[10] + left[14] * right[11];
        float r11 = left[3] * right[8] + left[7] * right[9] + left[11] * right[10] + left[15] * right[11];
        float r12 = left[0] * right[12] + left[4] * right[13] + left[8] * right[14] + left[12] * right[15];
        float r13 = left[1] * right[12] + left[5] * right[13] + left[9] * right[14] + left[13] * right[15];
        float r14 = left[2] * right[12] + left[6] * right[13] + left[10] * right[14] + left[14] * right[15];
        float r15 = left[3] * right[12] + left[7] * right[13] + left[11] * right[14] + left[15] * right[15];
        out[0] = r0;
        out[1] = r1;
        out[2] = r2;
        out[3] = r3;
        out[4] = r4;
        out[5] = r5;
        out[6] = r6;
        out[7] = r7;
        out[8] = r8;
        out[9] = r9;
        out[10] = r10;
        out[11] = r11;
        out[12] = r12;
        out[13] = r13;
        out[14] = r14;
        out[15] = r15;
    }

    /**
     * modelview 平移列的模长：{@code sqrt(m[12]² + m[13]² + m[14]²)}。
     *
     * @param columnMajor 列主序矩阵；null 或长度不足返回 {@link Float#NaN}
     * @return 平移列模长（格）；无法计算时为 NaN（自检按「不可信」处理）
     */
    public static float translationMagnitude(float[] columnMajor) {
        if (!isUsable(columnMajor)) {
            return Float.NaN;
        }
        return (float) magnitude(
                columnMajor[TRANSLATION_COLUMN_X],
                columnMajor[TRANSLATION_COLUMN_Y],
                columnMajor[TRANSLATION_COLUMN_Z]);
    }

    /**
     * 三维向量模长（double 精度）。
     *
     * <p>自检的期望值 {@code |origin − renderPos|} 由调用方<b>先做 double 相减</b>再交给本方法：
     * 大坐标（真机 origin 在 ±3000 量级）在 float 端相减会丢掉整格精度，那正是
     * {@code uOriginRel} 用 double 相减的同一条理由。</p>
     *
     * @param x X 分量
     * @param y Y 分量
     * @param z Z 分量
     * @return 模长；任一分量非有限时返回 NaN
     */
    public static double magnitude(double x, double y, double z) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            return Double.NaN;
        }
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * 自检判据：由固定管线栈读回的 modelview 平移列是否与期望的相机相对距离一致。
     *
     * <p>通过区间：{@code 0.5 × expected <= |平移列| <= 2 × expected + 6.0}。</p>
     *
     * <ul>
     *   <li><b>期望值过小</b>（&lt; {@link #TRANSLATION_MIN_EXPECTED}，例如 origin 与相机重合）
     *       或非有限 ⇒ 返回 true：此形态下「单位阵」与「正确矩阵」不可区分，不阻断绘制；</li>
     *   <li><b>模型矩阵非法</b>（NaN / Infinity）⇒ 返回 false（宁可回退也不画乱码）；</li>
     *   <li><b>矩阵栈保持单位阵</b>（真机表型）⇒ {@code |平移列| = 0} 落在下界外 ⇒ false。</li>
     * </ul>
     *
     * <p><b>已知盲区</b>（登记，不视为缺陷）：陈旧一帧的矩阵（模长与期望接近）无法用本不变量
     * 检出；能检出的是「矩阵栈没被更新 / 读到垃圾」这一类。误判代价不对称——判 false 只是永久
     * 回退 legacy（既有契约，观感不变形），判 true 才会画出错误空间，故判据偏向严格。</p>
     *
     * @param modelViewColumnMajor 列主序 modelview；null / 长度不足视为不可信
     * @param expectedMagnitude    期望模长 {@code |origin − renderPos|}（格）
     * @return true 表示平移列与期望一致（矩阵来源可信）
     */
    public static boolean translationMatches(float[] modelViewColumnMajor, double expectedMagnitude) {
        if (!isFinite(expectedMagnitude) || Math.abs(expectedMagnitude) < TRANSLATION_MIN_EXPECTED) {
            // 不可判别的形态：不阻断（宁可放过也不误杀可用环境）。
            return true;
        }
        float actual = translationMagnitude(modelViewColumnMajor);
        if (!isFinite(actual)) {
            return false;
        }
        double lower = Math.abs(expectedMagnitude) * TRANSLATION_LOWER_FACTOR;
        double upper = Math.abs(expectedMagnitude) * TRANSLATION_UPPER_FACTOR + TRANSLATION_ABSOLUTE_SLACK;
        return actual >= lower && actual <= upper;
    }

    private static boolean isUsable(float[] matrix) {
        return matrix != null && matrix.length >= MATRIX_ELEMENTS;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
