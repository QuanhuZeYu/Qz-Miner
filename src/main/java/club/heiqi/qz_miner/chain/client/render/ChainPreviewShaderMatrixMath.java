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
     * 平移方向一致性的默认容差（格）：覆盖 vanilla <b>第一人称</b>的相机空间偏移
     * （bob ≤0.5 + 眼位偏移 ≤1.0）。第三人称由调用方按拉回距离放宽
     * （{@code ChainPreviewShaderBackend.translationDirectionSlack()}）。
     */
    public static final float TRANSLATION_DIRECTION_SLACK = 6.0F;

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

    // ---------------------------------------------------------------- T48c-C 自检加固

    /**
     * 刚性判定的单位长度容差（±2%）。
     *
     * <p>世界渲染的 modelview 线性部分是<b>纯旋转</b>：1.7.10 {@code EntityRenderer.orientCamera}
     * 只施加旋转与平移（bob / 第三人称拉回 / 眼位偏移，均为平移），缩放只出现在投影栈与 GUI 路径。
     * float32 累积与驱动微误差在 1e-5 量级，2% 已远超合法误差；而线性塌缩（长度 0）与
     * 2× 缩放（长度 2.0）必然落在容差外。</p>
     */
    public static final float RIGID_LENGTH_TOLERANCE = 0.02F;

    /**
     * 刚性判定的正交容差（{@code |列点积| <= 2%}）。
     *
     * <p>与长度容差同量级：合法旋转三列两两正交（点积 0），错旋转/剪切会让点积迅速接近 ±1。</p>
     */
    public static final float RIGID_ORTHOGONALITY_TOLERANCE = 0.02F;

    /** 投影矩阵行列式下限：{@code |det| > 1e-9}，低于它视为退化（塌缩）矩阵。 */
    public static final double PROJECTION_DETERMINANT_MIN = 1.0e-9D;

    /** 相机矩阵自检结论（失败原因可直接进 describe 诊断）。 */
    public enum MatrixVerdict {
        /** 投影 + 线性部分 + 平移列（模长与方向一致性）全部通过。 */
        TRUSTWORTHY,
        /** 投影矩阵非有限 / [0][0] 或 [1][1] <= 0 / 行列式退化。 */
        PROJECTION_UNTRUSTED,
        /** modelview 的 3×3 线性部分不是刚性旋转（长度或正交性超容差）。 */
        LINEAR_PART_NOT_RIGID,
        /** 平移列模长与期望的 |origin − renderPos| 不符。 */
        TRANSLATION_MISMATCH,
        /** 平移列与「线性部分 × 相机相对原点」不一致（方向/轴向被搬动）。 */
        TRANSLATION_DIRECTION_MISMATCH
    }

    /**
     * modelview 的 3×3 线性部分是否为刚性旋转：三列单位长度且两两正交。
     *
     * <p>堵住<b>线性部分塌缩</b>与 <b>2× 缩放</b>两类「只校验平移列模长时必然漏过」的形态。</p>
     *
     * @param modelViewColumnMajor 列主序 modelview；null / 长度不足视为非刚性
     * @return 是否刚性
     */
    public static boolean linearPartIsRigid(float[] modelViewColumnMajor) {
        if (!isUsable(modelViewColumnMajor)) {
            return false;
        }
        float xx = modelViewColumnMajor[0];
        float xy = modelViewColumnMajor[1];
        float xz = modelViewColumnMajor[2];
        float yx = modelViewColumnMajor[4];
        float yy = modelViewColumnMajor[5];
        float yz = modelViewColumnMajor[6];
        float zx = modelViewColumnMajor[8];
        float zy = modelViewColumnMajor[9];
        float zz = modelViewColumnMajor[10];
        if (!isFinite(xx) || !isFinite(xy) || !isFinite(xz)
                || !isFinite(yx) || !isFinite(yy) || !isFinite(yz)
                || !isFinite(zx) || !isFinite(zy) || !isFinite(zz)) {
            return false;
        }
        if (!isUnitLength(xx, xy, xz) || !isUnitLength(yx, yy, yz) || !isUnitLength(zx, zy, zz)) {
            return false;
        }
        double dotXY = (double) xx * yx + (double) xy * yy + (double) xz * yz;
        double dotXZ = (double) xx * zx + (double) xy * zy + (double) xz * zz;
        double dotYZ = (double) yx * zx + (double) yy * zy + (double) yz * zz;
        return Math.abs(dotXY) <= RIGID_ORTHOGONALITY_TOLERANCE
                && Math.abs(dotXZ) <= RIGID_ORTHOGONALITY_TOLERANCE
                && Math.abs(dotYZ) <= RIGID_ORTHOGONALITY_TOLERANCE;
    }

    private static boolean isUnitLength(float x, float y, float z) {
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        return Math.abs(length - 1.0D) <= RIGID_LENGTH_TOLERANCE;
    }

    /**
     * 投影矩阵是否可信：16 个元素有限、{@code [0][0] > 0}、{@code [1][1] > 0}、行列式非退化。
     *
     * <p>刻意<b>不</b>检查 {@code [3][2] == -1}（列主序下标 11）：正交投影该位置为 0，
     * 而正交投影是合法形态（实测 ortho 矩阵 det = -3.9e-5，仍通过本判据），加了会误杀。</p>
     *
     * @param projectionColumnMajor 列主序投影矩阵；null / 长度不足视为不可信
     * @return 是否可信
     */
    public static boolean projectionIsSane(float[] projectionColumnMajor) {
        if (!isUsable(projectionColumnMajor)) {
            return false;
        }
        for (int index = 0; index < MATRIX_ELEMENTS; index++) {
            if (!isFinite(projectionColumnMajor[index])) {
                return false;
            }
        }
        if (!(projectionColumnMajor[0] > 0.0F) || !(projectionColumnMajor[5] > 0.0F)) {
            return false;
        }
        return Math.abs(determinant4x4(projectionColumnMajor)) > PROJECTION_DETERMINANT_MIN;
    }

    /**
     * 列主序 4×4 行列式（投影退化判定用；已与 LU 分解独立实现互证，3000 组随机矩阵最大差 4.5e-13）。
     *
     * @param columnMajor 列主序矩阵；null / 长度不足返回 0（视为退化）
     * @return 行列式
     */
    public static double determinant4x4(float[] columnMajor) {
        if (!isUsable(columnMajor)) {
            return 0.0D;
        }
        // 元素命名 m{row}{col}：列主序下标 = col * 4 + row。
        double m00 = columnMajor[0];
        double m10 = columnMajor[1];
        double m20 = columnMajor[2];
        double m30 = columnMajor[3];
        double m01 = columnMajor[4];
        double m11 = columnMajor[5];
        double m21 = columnMajor[6];
        double m31 = columnMajor[7];
        double m02 = columnMajor[8];
        double m12 = columnMajor[9];
        double m22 = columnMajor[10];
        double m32 = columnMajor[11];
        double m03 = columnMajor[12];
        double m13 = columnMajor[13];
        double m23 = columnMajor[14];
        double m33 = columnMajor[15];
        double s0 = m00 * m11 - m10 * m01;
        double s1 = m00 * m21 - m20 * m01;
        double s2 = m00 * m31 - m30 * m01;
        double s3 = m10 * m21 - m20 * m11;
        double s4 = m10 * m31 - m30 * m11;
        double s5 = m20 * m31 - m30 * m21;
        double c5 = m22 * m33 - m32 * m23;
        double c4 = m12 * m33 - m32 * m13;
        double c3 = m12 * m23 - m22 * m13;
        double c2 = m02 * m33 - m32 * m03;
        double c1 = m02 * m23 - m22 * m03;
        double c0 = m02 * m13 - m12 * m03;
        return s0 * c5 - s1 * c4 + s2 * c3 + s3 * c4 * 0.0D + s3 * c2 - s4 * c1 + s5 * c0;
    }

    /**
     * 平移列与「线性部分 × 相机相对原点」的偏差：{@code |t − R·d|}。
     *
     * <p><b>为什么这条不变量成立</b>：{@code ChainPreviewRenderer} 在世界坐标已按 {@code renderPos}
     * 平移的前提下对栈做 {@code glTranslated(d)}（{@code d = origin − renderPos}），因此
     * {@code modelview = M0 · T(d)}，而 1.7.10 的 {@code M0}（{@code orientCamera}）只含相机旋转
     * 与少量相机空间平移 {@code b}（bob ≤0.5、第三人称拉回 ≤4.0（{@code EntityRenderer:629}）、
     * 眼位偏移 ≤1.0）⇒ {@code t = R·(b + d)} ⇒ 残差 {@code = |R·b| = |b| ≤ 6}。</p>
     *
     * <p>它比单纯校验模长更强：平移列被搬到别的轴向（线性部分未同步旋转）时残差会立刻变大。
     * 但受同一条 slack 限制，当 {@code E} 与 slack 同量级（≲6 格）时判别力下降；E 很小时由
     * {@link #translationMatches} 的模长窗口负责（单位阵必然被它拦下）。</p>
     *
     * @param modelViewColumnMajor 列主序 modelview
     * @param dx                   相机相对原点 X（{@code origin − renderPos}，double 域）
     * @param dy                   相机相对原点 Y
     * @param dz                   相机相对原点 Z
     * @return 残差（格）；矩阵非法返回 NaN
     */
    public static double translationResidual(float[] modelViewColumnMajor, double dx, double dy, double dz) {
        if (!isUsable(modelViewColumnMajor)) {
            return Double.NaN;
        }
        double expectedX = modelViewColumnMajor[0] * dx + modelViewColumnMajor[4] * dy + modelViewColumnMajor[8] * dz;
        double expectedY = modelViewColumnMajor[1] * dx + modelViewColumnMajor[5] * dy + modelViewColumnMajor[9] * dz;
        double expectedZ = modelViewColumnMajor[2] * dx + modelViewColumnMajor[6] * dy + modelViewColumnMajor[10] * dz;
        double rx = modelViewColumnMajor[TRANSLATION_COLUMN_X] - expectedX;
        double ry = modelViewColumnMajor[TRANSLATION_COLUMN_Y] - expectedY;
        double rz = modelViewColumnMajor[TRANSLATION_COLUMN_Z] - expectedZ;
        return Math.sqrt(rx * rx + ry * ry + rz * rz);
    }

    /**
     * 残差是否在给定容差内（{@code residual <= slack}）。
     *
     * <p>不可判定语义与 {@link #translationMatches} 一致：相机相对原点或容差非有限 ⇒ 放行；
     * 但矩阵本身非法 ⇒ 失败闭合（由 {@link #linearPartIsRigid} 先行拦截）。</p>
     *
     * @param modelViewColumnMajor 列主序 modelview
     * @param dx                   相机相对原点 X
     * @param dy                   相机相对原点 Y
     * @param dz                   相机相对原点 Z
     * @param slack                容差（格）；典型 {@link #TRANSLATION_DIRECTION_SLACK} + 第三人称拉回上限
     * @return 是否通过
     */
    public static boolean translationFollowsLinearPart(
            float[] modelViewColumnMajor, double dx, double dy, double dz, double slack) {
        if (!isUsable(modelViewColumnMajor)) {
            return false;
        }
        if (!isFinite(dx) || !isFinite(dy) || !isFinite(dz) || !isFinite(slack)) {
            // 不可判别（与 translationMatches 同语义）：不阻断。
            return true;
        }
        return translationResidual(modelViewColumnMajor, dx, dy, dz) <= slack;
    }

    /**
     * 用列主序矩阵变换一个点：{@code out = m × (x, y, z, 1)}（诊断锚点投影用）。
     *
     * @param out    长度 &ge; 4 的输出（裁剪坐标）；null / 过短忽略
     * @param matrix 列主序矩阵；非法时 out 全写 0
     * @param x      局部 X
     * @param y      局部 Y
     * @param z      局部 Z
     */
    public static void transformPoint(float[] out, float[] matrix, float x, float y, float z) {
        if (out == null || out.length < 4) {
            return;
        }
        if (!isUsable(matrix)) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            out[3] = 0.0F;
            return;
        }
        for (int row = 0; row < 4; row++) {
            out[row] = matrix[0 * 4 + row] * x + matrix[1 * 4 + row] * y
                    + matrix[2 * 4 + row] * z + matrix[3 * 4 + row];
        }
    }

    /**
     * 相机矩阵总自检（T48c-C）：投影可信 → 线性部分刚性 → 平移列模长 → 平移-线性一致性。
     *
     * <p>顺序即优先级，也是诊断给出的原因优先级。<b>不可判定语义保持</b>：相机相对原点过小
     * （&lt; {@link #TRANSLATION_MIN_EXPECTED}）或非有限时，平移相关的两项放行，但投影与刚性
     * 仍必须通过。</p>
     *
     * <p><b>为什么要有 {@code cameraWarpActive} 开关</b>：原版在「下界传送门 / 反胃药水」生效时
     * （{@code EntityPlayerSP.timeInPortal > 0}）会对 modelview 施加<b>非均匀缩放</b>与额外旋转
     * （{@code EntityRenderer.setupCameraTransform:710-712}：{@code glRotatef} → {@code glScalef(1/f3,1,1)}
     * → {@code glRotatef}）。这种线性部分合法地非刚性，无条件刚性判据会把它判失败并<b>永久回退</b>
     * legacy（比原缺陷更重）。因此该状态下跳过「刚性 / 平移方向」两项依赖刚性的判据，
     * 投影与平移列模长仍必须通过。</p>
     *
     * @param projectionColumnMajor 列主序投影矩阵
     * @param modelViewColumnMajor  列主序 modelview
     * @param dx                    相机相对原点 X（= origin − renderPos）
     * @param dy                    相机相对原点 Y
     * @param dz                    相机相对原点 Z
     * @param cameraWarpActive      原版相机扭曲（非均匀缩放）是否生效
     * @param directionSlack        平移方向一致性容差（格）；第一人称 6.0、第三人称再加拉回上限
     * @return 结论
     */
    public static MatrixVerdict verifyCameraMatrices(
            float[] projectionColumnMajor, float[] modelViewColumnMajor,
            double dx, double dy, double dz, boolean cameraWarpActive, double directionSlack) {
        if (!projectionIsSane(projectionColumnMajor)) {
            return MatrixVerdict.PROJECTION_UNTRUSTED;
        }
        if (!cameraWarpActive && !linearPartIsRigid(modelViewColumnMajor)) {
            return MatrixVerdict.LINEAR_PART_NOT_RIGID;
        }
        if (!translationMatches(modelViewColumnMajor, magnitude(dx, dy, dz))) {
            return MatrixVerdict.TRANSLATION_MISMATCH;
        }
        if (!cameraWarpActive
                && !translationFollowsLinearPart(modelViewColumnMajor, dx, dy, dz, directionSlack)) {
            return MatrixVerdict.TRANSLATION_DIRECTION_MISMATCH;
        }
        return MatrixVerdict.TRUSTWORTHY;
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
