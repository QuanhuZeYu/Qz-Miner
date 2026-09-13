package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMatrixMath;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMatrixMath.MatrixVerdict;

/**
 * T48c-C 相机矩阵判据的独立边界复算（task-49 增量复核，preview-verifier）。
 *
 * <p>与施工方测试的分工：施工方测试多为「源码片段断言」（读 .java 文本核对调用顺序），
 * 本类只走<b>公共 API 的行为断言</b>——给定矩阵，验证 {@code verifyCameraMatrices} 的结论。
 * 期望结论由我自己的独立实现（Python）先复算一遍再落地，不引用施工方断言。</p>
 *
 * <p><b>标【漏过】的用例是刻意的</b>：它们断言「当前语义=放行」，把残余风险锁在测试里，
 * 使将来收紧判据时会立刻红（而不是让「以为拦住了」的错觉留在报告里）。</p>
 *
 * <p>矩阵约定与实现一致：列主序 float[16]；线性部分在列 0/1/2，平移列在下标 12/13/14。</p>
 */
public class T48cCVerdictBoundaryContractTest {

    /** 期望平移列模长与实际相机的一致性窗口（第一人称 slack）。 */
    private static final double FIRST_PERSON_SLACK = ChainPreviewShaderMatrixMath.TRANSLATION_DIRECTION_SLACK;

    /** 第三人称 slack = 6.0 + 拉回上限 4.0（与后端 translationDirectionSlack() 同口径）。 */
    private static final double THIRD_PERSON_SLACK = FIRST_PERSON_SLACK + 4.0D;

    /** origin − renderPos（17x17 地毯中心附近，取自 task-45 真机取证的几何尺度）。 */
    private static final double[] D = { 8.5D, 64.0D, 8.5D };

    private static final float[] IDENTITY = identity();

    @Test
    public void normalCameraStatesAreTrustworthy() {
        float[] rotation = rotation(37.0D, -21.0D);
        assertVerdict("透视 + 正确相机（第一人称眼高 1.62）", MatrixVerdict.TRUSTWORTHY,
                perspective(), camera(rotation, new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        assertVerdict("第三人称拉回 4.0 + bob（残差 4.538）", MatrixVerdict.TRUSTWORTHY,
                perspective(), camera(rotation, new double[] { 0.31D, 2.12D, 4.0D }), D, false, THIRD_PERSON_SLACK);
        assertVerdict("正交投影不得被误杀（det=-9.8e-6 仍 > 1e-9）", MatrixVerdict.TRUSTWORTHY,
                ortho(), camera(rotation, new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
    }

    @Test
    public void thirdPersonSlackMustActuallyWiden() {
        float[] mv = camera(rotation(37.0D, -21.0D), new double[] { 0.31D, 2.12D, 4.0D });
        assertVerdict("同一矩阵 slack=6 放行", MatrixVerdict.TRUSTWORTHY, perspective(), mv, D, false, 6.0D);
        assertVerdict("同一矩阵 slack=4 必须拦（两档锁定）", MatrixVerdict.TRANSLATION_DIRECTION_MISMATCH,
                perspective(), mv, D, false, 4.0D);
        assertVerdict("拉回 10.0 且只用第一人称 slack ⇒ 必须拦（第一人称不会退到 10）",
                MatrixVerdict.TRANSLATION_DIRECTION_MISMATCH, perspective(),
                camera(rotation(37.0D, -21.0D), new double[] { 0.0D, 1.62D, 10.0D }), D, false, 6.0D);
    }

    @Test
    public void realMachineSymptomAndDegenerationsAreCaught() {
        assertVerdict("单位阵（真机首要表型：FFP 栈未同步）", MatrixVerdict.TRANSLATION_MISMATCH,
                perspective(), IDENTITY, D, false, FIRST_PERSON_SLACK);
        assertVerdict("线性塌缩 (1,1,0)", MatrixVerdict.LINEAR_PART_NOT_RIGID, perspective(),
                camera(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
                        new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        assertVerdict("均匀 2x 缩放", MatrixVerdict.LINEAR_PART_NOT_RIGID, perspective(),
                camera(scale(2.0D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        assertVerdict("均匀 0.5x 缩放", MatrixVerdict.LINEAR_PART_NOT_RIGID, perspective(),
                camera(scale(0.5D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        assertVerdict("modelview NaN", MatrixVerdict.LINEAR_PART_NOT_RIGID, perspective(),
                translation(nanMatrix(), 0.0D, 0.0D, 0.0D), D, false, FIRST_PERSON_SLACK);
        assertVerdict("modelview +Inf", MatrixVerdict.LINEAR_PART_NOT_RIGID, perspective(),
                translation(infMatrix(), 0.0D, 0.0D, 0.0D), D, false, FIRST_PERSON_SLACK);
        assertVerdict("投影 [0][0]=0（零行）", MatrixVerdict.PROJECTION_UNTRUSTED, new float[16],
                camera(rotation(37.0D, -21.0D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        assertVerdict("投影 NaN", MatrixVerdict.PROJECTION_UNTRUSTED, withNaN(perspective()),
                camera(rotation(37.0D, -21.0D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
    }

    @Test
    public void projectionDeterminantGateActuallyFires() {
        // [0][0]、[1][1] 均 > 0，仅把 [2][3]（列主序 14）置 0 ⇒ det = m[0]*m[5]*m[14] = 0。
        // 若行列式判据形同虚设，这条会退化为 TRUSTWORTHY（模长/刚性都合法）。
        float[] degenerate = perspective();
        degenerate[14] = 0.0F;
        assertVerdict("投影行列式退化（[0][0]/[1][1] 正常）", MatrixVerdict.PROJECTION_UNTRUSTED,
                degenerate, camera(rotation(37.0D, -21.0D), new double[] { 0.0D, 1.62D, 0.0D }),
                D, false, FIRST_PERSON_SLACK);
        Assert.assertTrue("该矩阵的 [0][0]、[1][1] 必须仍为正（否则测的是别的分支）",
                degenerate[0] > 0.0F && degenerate[5] > 0.0F);
    }

    @Test
    public void translationMovedToAnotherAxisIsCaught() {
        float[] rotation = rotation(37.0D, -21.0D);
        double[] moved = { D[0], D[2], D[1] };
        float[] mv = translation(rotation, matvec3(rotation, moved));
        assertVerdict("平移换轴但模长相同（残差 78.49）", MatrixVerdict.TRANSLATION_DIRECTION_MISMATCH,
                perspective(), mv, D, false, FIRST_PERSON_SLACK);

        float[] base = matvec3(rotation, D);
        float[] negated = { -base[0], -base[1], -base[2] };
        assertVerdict("平移整列取反（残差 130.24）", MatrixVerdict.TRANSLATION_DIRECTION_MISMATCH,
                perspective(), translation(rotation, negated), D, false, FIRST_PERSON_SLACK);
    }

    @Test
    public void residualIsExactlyTheCameraSpaceOffset() {
        float[] rotation = rotation(37.0D, -21.0D);
        double[] offsets = { 1.62D, 2.0D, 4.537676D, 6.5D };
        for (double offset : offsets) {
            float[] mv = camera(rotation, new double[] { 0.0D, offset, 0.0D });
            double residual = ChainPreviewShaderMatrixMath.translationResidual(mv, D[0], D[1], D[2]);
            Assert.assertEquals("残差必须恰等于 |e_rel|（相机空间偏移）", offset, residual, 1.0e-4D);
        }
    }

    @Test
    public void portalWarpExemptionOnlyRelaxesRigidityAndDirection() {
        float[] rotation = rotation(37.0D, -21.0D);
        float[] warped = translation(scale(0.5D), matvec3(rotation, new double[] { D[0], D[1] - 1.62D, D[2] }));
        assertVerdict("warp 生效：非刚性但投影/模长通过 ⇒ 放行（防误回退）", MatrixVerdict.TRUSTWORTHY,
                perspective(), warped, D, true, FIRST_PERSON_SLACK);
        assertVerdict("warp 不豁免投影（NaN 仍拦）", MatrixVerdict.PROJECTION_UNTRUSTED, withNaN(perspective()),
                warped, D, true, FIRST_PERSON_SLACK);
        assertVerdict("warp 不豁免平移列模长（单位阵仍拦）", MatrixVerdict.TRANSLATION_MISMATCH,
                perspective(), IDENTITY, D, true, FIRST_PERSON_SLACK);
    }

    @Test
    public void registeredResidualRisksStayOpenUntilDeliberatelyTightened() {
        // 【漏过 1】单位阵 + 平移列恰等于 d：自洽到无法用矩阵自身区分（线性部分是合法相机姿态）。
        assertVerdict("【漏过】单位阵 + 平移列 == d", MatrixVerdict.TRUSTWORTHY, perspective(),
                translation(identity(), D[0], D[1], D[2]), D, false, FIRST_PERSON_SLACK);
        // 【漏过 2】陈旧一帧（旋转差 1 度）：刚性、模长、残差全在容差内。
        assertVerdict("【漏过】陈旧一帧（yaw 差 1 度）", MatrixVerdict.TRUSTWORTHY, perspective(),
                camera(rotation(38.0D, -21.0D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        // 【漏过 3】镜像（det = -1）：三列仍单位长度且两两正交 ⇒ 刚性判据看不出（最小补法：det(L) > 0）。
        assertVerdict("【漏过】镜像 det=-1", MatrixVerdict.TRUSTWORTHY, perspective(),
                camera(new float[] { -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 },
                        new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        // 【漏过 5】陈旧投影：只查形态（有限 / 对角为正 / 非退化），不查与相机 FOV、视口的一致性
        //          ⇒ far/near/fov 全错但形态合法的投影仍通过（最小补法：与 CPU 侧 FOV/aspect 期望比对）。
        float[] staleProjection = perspective();
        staleProjection[5] = 2.8562F; // 等效 FOV 约 40°（真实 70°），形态完全合法
        assertVerdict("【漏过】陈旧/错误 FOV 的投影", MatrixVerdict.TRUSTWORTHY, staleProjection,
                camera(rotation(37.0D, -21.0D), new double[] { 0.0D, 1.62D, 0.0D }), D, false, FIRST_PERSON_SLACK);
        // 【漏过 4】相机贴合 origin（E < 1e-3）⇒ 模长判据放行，方向判据因残差小而形同虚设。
        assertVerdict("【漏过】相机贴 origin（E<1e-3）且平移陈旧", MatrixVerdict.TRUSTWORTHY, perspective(),
                translation(rotation(37.0D, -21.0D), 1.0D, 0.0D, 0.0D),
                new double[] { 0.0005D, 0.0D, 0.0D }, false, FIRST_PERSON_SLACK);
    }

    // ---------------------------------------------------------------- 构造与断言

    private static void assertVerdict(String name, MatrixVerdict expected, float[] projection, float[] modelView,
            double[] d, boolean warp, double slack) {
        MatrixVerdict actual = ChainPreviewShaderMatrixMath.verifyCameraMatrices(
                projection, modelView, d[0], d[1], d[2], warp, slack);
        Assert.assertEquals(name, expected, actual);
    }

    private static float[] identity() {
        float[] m = new float[ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS];
        m[0] = 1.0F; m[5] = 1.0F; m[10] = 1.0F; m[15] = 1.0F;
        return m;
    }

    /** 行主序 R = Ry(yaw) * Rx(pitch) → 列主序 float[16]。 */
    private static float[] rotation(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double[][] r = {
            { cy, sy * sp, sy * cp },
            { 0.0D, cp, -sp },
            { -sy, cy * sp, cy * cp },
        };
        float[] m = identity();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[col * 4 + row] = (float) r[row][col];
            }
        }
        return m;
    }

    private static float[] scale(double factor) {
        float[] m = identity();
        m[0] = (float) factor; m[5] = (float) factor; m[10] = (float) factor;
        return m;
    }

    private static float[] translation(float[] linear, double x, double y, double z) {
        float[] m = linear.clone();
        m[12] = (float) x; m[13] = (float) y; m[14] = (float) z;
        return m;
    }

    private static float[] translation(float[] linear, float[] t) {
        return translation(linear, t[0], t[1], t[2]);
    }

    /** L * v（列主序线性部分；与 translationResidual 的 expected 公式同口径）。 */
    private static float[] matvec3(float[] m, double[] v) {
        return new float[] {
            (float) (m[0] * v[0] + m[4] * v[1] + m[8] * v[2]),
            (float) (m[1] * v[0] + m[5] * v[1] + m[9] * v[2]),
            (float) (m[2] * v[0] + m[6] * v[1] + m[10] * v[2]),
        };
    }

    /** 正确相机：线性部分为旋转，平移列 = R * (d − e_rel)（renderer 已按 renderPos 平移后再 glTranslated 的结果）。 */
    private static float[] camera(float[] rotation, double[] eyeRelative) {
        double[] delta = { D[0] - eyeRelative[0], D[1] - eyeRelative[1], D[2] - eyeRelative[2] };
        return translation(rotation, matvec3(rotation, delta));
    }

    private static float[] nanMatrix() {
        float[] m = new float[16];
        m[0] = Float.NaN;
        for (int i = 1; i < 16; i++) {
            m[i] = 1.0F;
        }
        return m;
    }

    private static float[] infMatrix() {
        float[] m = new float[16];
        m[0] = Float.POSITIVE_INFINITY;
        for (int i = 1; i < 16; i++) {
            m[i] = 1.0F;
        }
        return m;
    }

    private static float[] withNaN(float[] source) {
        float[] m = source.clone();
        m[0] = Float.NaN;
        return m;
    }

    /** 透视投影（fov 70 / aspect 16:9 / near 0.05 / far 1000；det = -0.114734）。 */
    private static float[] perspective() {
        double fov = 70.0D, aspect = 16.0D / 9.0D, near = 0.05D, far = 1000.0D;
        double f = 1.0D / Math.tan(Math.toRadians(fov / 2.0D));
        float[] m = new float[16];
        m[0] = (float) (f / aspect);
        m[5] = (float) f;
        m[10] = (float) ((far + near) / (near - far));
        m[11] = -1.0F;
        m[14] = (float) (2.0D * far * near / (near - far));
        return m;
    }

    /** 正交投影（det = -9.80441e-6，刻意贴近行列式下限以证明不误杀）。 */
    private static float[] ortho() {
        double left = -17.0D, right = 17.0D, bottom = -12.0D, top = 12.0D, near = 0.05D, far = 1000.0D;
        float[] m = identity();
        m[0] = (float) (2.0D / (right - left));
        m[5] = (float) (2.0D / (top - bottom));
        m[10] = (float) (-2.0D / (far - near));
        m[12] = (float) (-(right + left) / (right - left));
        m[13] = (float) (-(top + bottom) / (top - bottom));
        m[14] = (float) (-(far + near) / (far - near));
        return m;
    }
}
