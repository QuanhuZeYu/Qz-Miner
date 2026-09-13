package club.heiqi.qz_miner.chain.client.render;

import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@link ChainPreviewShaderMatrixMath} 的纯 JVM 契约测试（T48c-A）。
 *
 * <p>着色器改用显式 MVP 之后，「矩阵乘法是否正确」「读到的矩阵是否可信」不再只能靠真机观感
 * 判断：本测试用两套独立实现（生产用展开式 + 测试用三重循环参考模型）互证，并把最容易错的
 * <b>列主序约定</b>与自检容差用反例锁死。</p>
 */
public class ChainPreviewShaderMatrixMathTest {

    private static final float EPS = 1.0e-6F;

    // ------------------------------------------------------------------ 列主序约定

    /**
     * 平移列在 12/13/14（列主序），不是 3/7/11（行主序）。
     *
     * <p>反例锁：把同一个平移量按行主序塞进 3/7/11，{@link ChainPreviewShaderMatrixMath#translationMagnitude}
     * 必须读出 0——若有人改成行主序解读，本断言立即失败。</p>
     */
    @Test
    public void translationLivesInColumnThreeNotRowThree() {
        float[] columnMajor = translate(1.25F, -0.5F, 2.75F);
        Assert.assertEquals("列主序平移列下标必须是 12/13/14",
                12, ChainPreviewShaderMatrixMath.TRANSLATION_COLUMN_X);
        Assert.assertEquals(13, ChainPreviewShaderMatrixMath.TRANSLATION_COLUMN_Y);
        Assert.assertEquals(14, ChainPreviewShaderMatrixMath.TRANSLATION_COLUMN_Z);
        Assert.assertEquals("平移列模长",
                (float) Math.sqrt(1.25 * 1.25 + 0.25 + 2.75 * 2.75),
                ChainPreviewShaderMatrixMath.translationMagnitude(columnMajor), EPS);

        float[] rowMajor = identity();
        rowMajor[3] = 1.25F;
        rowMajor[7] = -0.5F;
        rowMajor[11] = 2.75F;
        Assert.assertEquals("行主序存放的平移不得被当成平移（约定锁）",
                0.0F, ChainPreviewShaderMatrixMath.translationMagnitude(rowMajor), 0.0F);
    }

    /** 单位阵必须逐值恒等（左乘与右乘）。 */
    @Test
    public void identityIsExactOnBothSides() {
        float[] matrix = {
            0.5F, 0.25F, 0.125F, 0.0625F,
            -0.75F, 1.5F, -2.25F, 3.0F,
            0.03125F, -0.5F, 0.875F, -1.0F,
            1.25F, -0.5F, 2.75F, 1.0F,
        };
        float[] left = new float[16];
        float[] right = new float[16];
        ChainPreviewShaderMatrixMath.multiply4x4(left, identity(), matrix);
        ChainPreviewShaderMatrixMath.multiply4x4(right, matrix, identity());
        Assert.assertArrayEquals("I × M 必须逐值等于 M", matrix, left, 0.0F);
        Assert.assertArrayEquals("M × I 必须逐值等于 M", matrix, right, 0.0F);
    }

    // ------------------------------------------------------------------ 与独立参考模型互证

    /** 生产用展开式必须与测试侧三重循环参考模型在 500 组随机矩阵上逐值相同。 */
    @Test
    public void unrolledMultiplyMatchesTripleLoopReference() {
        Random random = new Random(20260913L);
        float[] left = new float[16];
        float[] right = new float[16];
        for (int round = 0; round < 500; round++) {
            fill(random, left);
            fill(random, right);
            float[] actual = new float[16];
            ChainPreviewShaderMatrixMath.multiply4x4(actual, left, right);
            float[] expected = naiveMultiply(left, right);
            Assert.assertArrayEquals("第 " + round + " 组随机矩阵必须逐值相同", expected, actual, 0.0F);
        }
    }

    /**
     * 与 GL 固定管线语义一致：{@code (P × M) × v == P × (M × v)}。
     *
     * <p>这是「MVP 先乘后上传」与「顶点阶段分两步变换」等价的可断言形式（1.7.10 legacy 路径
     * 就是固定管线一步完成，两条路径必须给出同一个裁剪坐标）。</p>
     */
    @Test
    public void combinedMatrixEqualsStagedTransformsPerVertex() {
        float[] projection = perspective(70.0F, 1.7778F, 0.05F, 512.0F);
        float[] modelView = multiply(rotationY(23.5F), translate(-3.25F, 0.5F, 8.75F));
        float[] combined = new float[16];
        ChainPreviewShaderMatrixMath.multiply4x4(combined, projection, modelView);

        float[][] samples = {
            {0.0F, 0.0F, 0.0F, 1.0F},
            {0.5F, 0.045F, 0.0225F, 1.0F},
            {-17.0F, 1.0F, 17.0F, 1.0F},
            {0.0F, 0.0F, 1.0F, 0.0F},
            {0.0F, 1.0F, 0.0F, 0.0F},
        };
        for (float[] vertex : samples) {
            float[] staged = transform(projection, transform(modelView, vertex));
            float[] oneStep = transform(combined, vertex);
            for (int i = 0; i < 4; i++) {
                Assert.assertEquals("分量 " + i + " 必须一致", staged[i], oneStep[i], 1.0e-4F);
            }
        }
    }

    /**
     * 旋转不改变平移列模长——自检判据成立的前提。
     *
     * <p>真机上 {@code glTranslated(origin − renderPos)} 之前还叠着相机旋转，所以平移列的模长
     * 才会等于 {@code |origin − renderPos|} 而不是 0。</p>
     */
    @Test
    public void rotationPreservesTranslationColumnMagnitude() {
        float[] translation = {1.25F, -0.5F, 2.75F};
        float expected = (float) Math.sqrt(1.25 * 1.25 + 0.25 + 2.75 * 2.75);
        for (float yaw : new float[] {0.0F, 17.0F, 45.0F, 90.0F, 123.4F, 270.0F}) {
            float[] modelView = multiply(rotationY(yaw), translate(translation[0], translation[1], translation[2]));
            Assert.assertEquals("yaw=" + yaw + " 时模长必须守恒",
                    expected, ChainPreviewShaderMatrixMath.translationMagnitude(modelView), 1.0e-5F);
        }
    }

    // ------------------------------------------------------------------ 自检真值表

    /**
     * 自检判据真值表：通过区间 {@code [0.5E, 2E + 6.0]}。
     *
     * <p>最要紧的一行是「单位阵 + 期望非零 ⇒ 判失败」：真机表型正是矩阵栈没被驱动更新时
     * 内建/显式矩阵一起退化成单位阵。</p>
     */
    @Test
    public void selfCheckTruthTable() {
        Assert.assertTrue("健康栈必须通过",
                ChainPreviewShaderMatrixMath.translationMatches(translate(3.0F, 0.0F, 0.0F), 3.02D));
        Assert.assertTrue("行走 bob 抖动必须通过",
                ChainPreviewShaderMatrixMath.translationMatches(translate(3.07F, 0.0F, 0.0F), 3.02D));
        Assert.assertTrue("第三人称相机拉回（≤4 格）必须通过",
                ChainPreviewShaderMatrixMath.translationMatches(translate(7.02F, 0.0F, 0.0F), 3.02D));

        Assert.assertFalse("单位阵（矩阵栈未被更新）必须被判失败",
                ChainPreviewShaderMatrixMath.translationMatches(identity(), 3.02D));
        Assert.assertFalse("期望很小（0.4 格）时单位阵同样必须判失败",
                ChainPreviewShaderMatrixMath.translationMatches(identity(), 0.4D));
        Assert.assertFalse("低于下界 0.5E 必须判失败",
                ChainPreviewShaderMatrixMath.translationMatches(translate(1.5F, 0.0F, 0.0F), 3.02D));
        Assert.assertTrue("略高于下界 0.5E 必须通过",
                ChainPreviewShaderMatrixMath.translationMatches(translate(1.6F, 0.0F, 0.0F), 3.02D));
        Assert.assertTrue("刚好在上界内必须通过",
                ChainPreviewShaderMatrixMath.translationMatches(translate(12.0F, 0.0F, 0.0F), 3.02D));
        Assert.assertFalse("超出上界 2E+6 必须判失败",
                ChainPreviewShaderMatrixMath.translationMatches(translate(12.1F, 0.0F, 0.0F), 3.02D));

        Assert.assertTrue("期望 ≈ 0（origin 与相机重合）不可判别时必须放行",
                ChainPreviewShaderMatrixMath.translationMatches(identity(), 0.0D));
        Assert.assertTrue("期望低于判别下限时同样放行",
                ChainPreviewShaderMatrixMath.translationMatches(identity(), 1.0e-4D));
        Assert.assertTrue("期望为非有限值时放行（不得因此误杀可用环境）",
                ChainPreviewShaderMatrixMath.translationMatches(identity(), Double.NaN));

        float[] nanMatrix = translate(1.0F, 0.0F, 0.0F);
        nanMatrix[13] = Float.NaN;
        Assert.assertFalse("平移列含 NaN 必须判失败（宁可回退也不画乱码）",
                ChainPreviewShaderMatrixMath.translationMatches(nanMatrix, 3.02D));
        float[] infiniteMatrix = translate(1.0F, 0.0F, 0.0F);
        infiniteMatrix[14] = Float.POSITIVE_INFINITY;
        Assert.assertFalse("平移列含 Infinity 必须判失败",
                ChainPreviewShaderMatrixMath.translationMatches(infiniteMatrix, 3.02D));
        Assert.assertFalse("矩阵为 null 必须判失败（fail-closed）",
                ChainPreviewShaderMatrixMath.translationMatches(null, 3.02D));
        Assert.assertFalse("矩阵长度不足必须判失败",
                ChainPreviewShaderMatrixMath.translationMatches(new float[15], 3.02D));
    }

    /** 容差的取值理由必须被钉住：绝对松弛要覆盖原版第三人称相机拉回上限。 */
    @Test
    public void toleranceCoversVanillaThirdPersonCameraPullback() {
        Assert.assertTrue("下界系数必须小于 1（否则 bob 抖动会误判）",
                ChainPreviewShaderMatrixMath.TRANSLATION_LOWER_FACTOR < 1.0F);
        Assert.assertTrue("下界系数必须大于 0（否则失去检测力）",
                ChainPreviewShaderMatrixMath.TRANSLATION_LOWER_FACTOR > 0.0F);
        Assert.assertTrue("绝对松弛必须覆盖原版第三人称相机拉回上限 4 格",
                ChainPreviewShaderMatrixMath.TRANSLATION_ABSOLUTE_SLACK >= 4.0F);
        Assert.assertTrue("期望下限必须为正且远小于常用链路距离（0.5~30 格）",
                ChainPreviewShaderMatrixMath.TRANSLATION_MIN_EXPECTED > 0.0F
                        && ChainPreviewShaderMatrixMath.TRANSLATION_MIN_EXPECTED < 0.01F);
    }

    // ------------------------------------------------------------------ 退化输入 / 精度

    /** 非法输入不得抛异常、不得改写输出（渲染帧不得因参数问题产生半成品矩阵）。 */
    @Test
    public void illegalInputsNeitherThrowNorCorruptOutput() {
        float[] out = {7.0F, 7.0F, 7.0F, 7.0F};
        ChainPreviewShaderMatrixMath.multiply4x4(null, identity(), identity());
        ChainPreviewShaderMatrixMath.multiply4x4(out, new float[4], identity());
        ChainPreviewShaderMatrixMath.multiply4x4(out, identity(), null);
        Assert.assertArrayEquals("非法输入必须保持输出不变", new float[] {7.0F, 7.0F, 7.0F, 7.0F}, out, 0.0F);

        Assert.assertTrue("null 矩阵的平移列模长必须是 NaN",
                Float.isNaN(ChainPreviewShaderMatrixMath.translationMagnitude(null)));
        Assert.assertTrue("长度不足的矩阵同样返回 NaN",
                Float.isNaN(ChainPreviewShaderMatrixMath.translationMagnitude(new float[15])));
        Assert.assertEquals("非法分量的模长必须是 NaN",
                Double.isNaN(ChainPreviewShaderMatrixMath.magnitude(Double.NaN, 0.0D, 0.0D)), true);
    }

    /** 允许 out 与输入别名（后端为省内存复用同一数组时必须仍然正确）。 */
    @Test
    public void multiplyToleratesAliasedOutput() {
        float[] left = multiply(rotationY(31.0F), translate(2.0F, -1.0F, 4.5F));
        float[] right = perspective(70.0F, 1.7778F, 0.05F, 512.0F);
        float[] expected = naiveMultiply(left, right);

        float[] aliasedLeft = left.clone();
        ChainPreviewShaderMatrixMath.multiply4x4(aliasedLeft, aliasedLeft, right);
        Assert.assertArrayEquals("out == left 时必须正确", expected, aliasedLeft, 0.0F);

        float[] aliasedRight = right.clone();
        ChainPreviewShaderMatrixMath.multiply4x4(aliasedRight, left, aliasedRight);
        Assert.assertArrayEquals("out == right 时必须正确", expected, aliasedRight, 0.0F);
    }

    /** 大坐标必须先做 double 相减：float 端相减会丢掉整格精度，期望模长随之失真。 */
    @Test
    public void magnitudeKeepsPrecisionAtLargeCoordinates() {
        // 真机世界坐标可达 ±3000 万；这里取 300 万量级——该量级下 float 的间距是 0.25 格，
        // 相机与 origin 之间 0.1 格的偏移在 float 端会被直接抹平（数值已离线验算）。
        double originX = 3_000_000.5D;
        double renderPosX = 3_000_000.6D;
        double expected = 0.1D;
        Assert.assertEquals("double 域相减必须保留 0.1 格精度",
                expected, ChainPreviewShaderMatrixMath.magnitude(originX - renderPosX, 0.0D, 0.0D), 1.0e-9D);

        float floatSubtracted = Math.abs((float) originX - (float) renderPosX);
        Assert.assertEquals("反例：float 端相减把 0.1 格偏移抹成 0", 0.0F, floatSubtracted, 0.0F);
        Assert.assertTrue("反例必须真的失真（否则该断言没有意义）",
                Math.abs(floatSubtracted - expected) > 1.0e-3D);
    }

    // ------------------------------------------------------------------ 辅助

    private static void fill(Random random, float[] matrix) {
        for (int i = 0; i < matrix.length; i++) {
            matrix[i] = (random.nextFloat() - 0.5F) * 6.0F;
        }
    }

    private static float[] naiveMultiply(float[] left, float[] right) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    sum += left[k * 4 + row] * right[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    private static float[] transform(float[] matrix, float[] vector) {
        float[] out = new float[4];
        for (int row = 0; row < 4; row++) {
            out[row] = matrix[0 * 4 + row] * vector[0]
                    + matrix[1 * 4 + row] * vector[1]
                    + matrix[2 * 4 + row] * vector[2]
                    + matrix[3 * 4 + row] * vector[3];
        }
        return out;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] out = new float[16];
        ChainPreviewShaderMatrixMath.multiply4x4(out, left, right);
        return out;
    }

    private static float[] identity() {
        float[] matrix = new float[16];
        matrix[0] = 1.0F;
        matrix[5] = 1.0F;
        matrix[10] = 1.0F;
        matrix[15] = 1.0F;
        return matrix;
    }

    private static float[] translate(float x, float y, float z) {
        float[] matrix = identity();
        matrix[12] = x;
        matrix[13] = y;
        matrix[14] = z;
        return matrix;
    }

    private static float[] rotationY(float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] matrix = identity();
        // 列主序：列 0 = (cos, 0, -sin)、列 2 = (sin, 0, cos)。
        matrix[0] = cos;
        matrix[2] = -sin;
        matrix[8] = sin;
        matrix[10] = cos;
        return matrix;
    }

    private static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = (float) (1.0D / Math.tan(Math.toRadians(fovY) / 2.0D));
        float[] matrix = new float[16];
        matrix[0] = f / aspect;
        matrix[5] = f;
        matrix[10] = (far + near) / (near - far);
        matrix[11] = -1.0F;
        matrix[14] = (2.0F * far * near) / (near - far);
        return matrix;
    }
}
