package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.BuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B2.3（task-16b）：Builder 消费 {@link ChainPreviewSemanticClass} 载体并写入 aAux.x。
 *
 * <p>契约：载体与目标迭代顺序严格同序；顶点去重后语义类别与 appearOrder 同源，取最小
 * incident 目标（即最小出现序号）的类别；载体缺失/越界/非法值按 255 兜底且可观察、绝不错位；
 * colors 流不因类别分叉（builtin 基线）。主夹具为相邻链，隔离方块只作补充。</p>
 */
public class ChainPreviewBuilderSemanticClassTest {

    @Test
    public void alignedCarrierWritesEveryVertexClassFromItsMinIncidentOrder() {
        int[] carrier = {ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.TRUNCATED,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL};
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            chain(4), visuals(), 0.045F, carrier);

        assertClassFollowsOrder(mesh, carrier);
        Assert.assertTrue("至少出现两种类别，避免断言平凡通过", distinctClasses(mesh) >= 2);
    }

    @Test
    public void sharedJunctionKeepsClassOfTheMinimumIncidentTarget() {
        int[] carrier = {ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL};
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            chain(2), visuals(), 0.045F, carrier);

        // x = 1.0 直通格点由目标 0 与目标 1 共享 → order 取 0，类别必须取目标 0 的 REMOTE(2)。
        int shared = 0;
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            if (Math.abs(vertices[vertex * 3] - 1.0F) > 0.0005F) {
                continue;
            }
            shared++;
            Assert.assertEquals(0, appearOrder(aux, vertex));
            Assert.assertEquals(
                ChainPreviewSemanticClass.REMOTE_PREDICTED, aux[vertex * 4] & 0xFF);
        }
        Assert.assertTrue("共享接头顶点缺失", shared > 0);
        assertClassFollowsOrder(mesh, carrier);
    }

    @Test
    public void duplicateTargetKeepsFirstOccurrenceClass() {
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(2, 0, 0);
        int[] carrier = {ChainPreviewSemanticClass.TRUNCATED,
            ChainPreviewSemanticClass.EXECUTED,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL};

        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Arrays.asList(first, first, second), visuals(), 0.045F, carrier);

        assertBlockClass(mesh, 0, ChainPreviewSemanticClass.TRUNCATED);
        assertBlockClass(mesh, 2, ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertFalse("重复目标不得产生 EXECUTED 类别",
            containsClass(mesh, ChainPreviewSemanticClass.EXECUTED));
    }

    @Test
    public void shortCarrierFallsBackToUndefinedWithoutMisalignment() {
        long fallbackBefore = ChainPreviewMeshBuilder.getSemanticClassFallbackTotal();
        int[] carrier = {ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.PRIMARY_LOCAL};

        BuildSession session = new ChainPreviewMeshBuilder().begin(
            scattered(4), visuals(), 0.045F, carrier);
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();

        Assert.assertEquals(2, session.getSemanticClassFallbackCount());
        Assert.assertEquals(
            fallbackBefore + 2L, ChainPreviewMeshBuilder.getSemanticClassFallbackTotal());
        assertBlockClass(mesh, 0, ChainPreviewSemanticClass.REMOTE_PREDICTED);
        assertBlockClass(mesh, 2, ChainPreviewSemanticClass.PRIMARY_LOCAL);
        assertBlockClass(mesh, 4, ChainPreviewSemanticClass.UNDEFINED);
        assertBlockClass(mesh, 6, ChainPreviewSemanticClass.UNDEFINED);
    }

    @Test
    public void invalidClassValuesFallBackToUndefinedAndAreObservable() {
        long fallbackBefore = ChainPreviewMeshBuilder.getSemanticClassFallbackTotal();
        int[] carrier = {99, -1, ChainPreviewSemanticClass.UNDEFINED,
            ChainPreviewSemanticClass.EXECUTED};

        BuildSession session = new ChainPreviewMeshBuilder().begin(
            scattered(4), visuals(), 0.045F, carrier);
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();

        Assert.assertEquals(2, session.getSemanticClassFallbackCount());
        Assert.assertEquals(
            fallbackBefore + 2L, ChainPreviewMeshBuilder.getSemanticClassFallbackTotal());
        assertBlockClass(mesh, 0, ChainPreviewSemanticClass.UNDEFINED);
        assertBlockClass(mesh, 2, ChainPreviewSemanticClass.UNDEFINED);
        assertBlockClass(mesh, 4, ChainPreviewSemanticClass.UNDEFINED);
        assertBlockClass(mesh, 6, ChainPreviewSemanticClass.EXECUTED);
    }

    @Test
    public void absentCarrierKeepsUndefinedAndIsNotCountedAsFallback() {
        BuildSession session = new ChainPreviewMeshBuilder().begin(chain(3), visuals(), 0.045F);
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();

        Assert.assertEquals(0, session.getSemanticClassFallbackCount());
        Assert.assertFalse("未提供载体时全部应为 255", containsDefinedClass(mesh));
    }

    @Test
    public void classesAreDeterministicPerGenerationAndResetOnNextBuild() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        int[] carrier = {ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.PRIMARY_LOCAL};
        ChainPreviewMesh first = builder.build(chain(2), visuals(), 0.045F, carrier);
        ChainPreviewMesh second = builder.build(chain(2), visuals(), 0.045F, carrier);
        Assert.assertArrayEquals("同输入重建必须逐字节一致", first.getAux(), second.getAux());

        int[] other = {ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.TRUNCATED};
        ChainPreviewMesh third = builder.build(chain(2), visuals(), 0.045F, other);
        assertClassFollowsOrder(third, other);
        Assert.assertFalse("上一代类别不得泄漏",
            containsClass(third, ChainPreviewSemanticClass.REMOTE_PREDICTED));
    }

    @Test
    public void builtinColorStreamStaysBaselineWhileClassesVary() {
        int[] carrier = {ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.TRUNCATED,
            ChainPreviewSemanticClass.EXECUTED};
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            chain(3), visuals(), 0.045F, carrier);

        float[] colors = mesh.getColors();
        Assert.assertEquals(mesh.getVertexFloatCount() / 3 * 4, colors.length);
        for (int offset = 0; offset < colors.length; offset += 4) {
            Assert.assertEquals(0.25F, colors[offset], 0.0F);
            Assert.assertEquals(0.9F, colors[offset + 1], 0.0F);
            Assert.assertEquals(1.0F, colors[offset + 2], 0.0F);
            Assert.assertTrue(colors[offset + 3] >= 0.2F && colors[offset + 3] <= 0.8F);
        }
        Assert.assertTrue("类别必须实际分叉，颜色流仍保持基线", distinctClasses(mesh) >= 2);
    }

    private static List<ChainTarget> chain(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index, 0, 0));
        }
        return targets;
    }

    /** 间隔 2 的隔离方块；索引 i 的方块世界 x = i * 2。 */
    private static List<ChainTarget> scattered(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }
        return targets;
    }

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    }

    /** 独立复算：class 必须等于「该顶点最小 incident 目标（= appearOrder）」对应载体的合法值。 */
    private static void assertClassFollowsOrder(ChainPreviewMesh mesh, int[] carrier) {
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            int order = appearOrder(aux, vertex);
            Assert.assertEquals(
                "vertex " + vertex + " order " + order + " 类别与序号必须同源",
                expectedClass(carrier, order),
                aux[vertex * 4] & 0xFF);
        }
    }

    private static int expectedClass(int[] carrier, int order) {
        if (order == ChainPreviewMesh.APPEAR_ORDER_UNDEFINED
                || order < 0
                || order >= carrier.length) {
            return ChainPreviewSemanticClass.UNDEFINED;
        }
        int value = carrier[order];
        if (value >= ChainPreviewSemanticClass.PRIMARY_LOCAL
                && value <= ChainPreviewSemanticClass.EXECUTED) {
            return value;
        }
        return ChainPreviewSemanticClass.UNDEFINED;
    }

    private static void assertBlockClass(ChainPreviewMesh mesh, int worldX, int expectedClass) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.03F || localX > worldX + 1.03F) {
                continue;
            }
            found++;
            Assert.assertEquals(
                "block@" + worldX + " vertex " + vertex,
                expectedClass,
                aux[vertex * 4] & 0xFF);
        }
        Assert.assertTrue("block@" + worldX + " 顶点缺失", found > 0);
    }

    private static boolean containsClass(ChainPreviewMesh mesh, int semanticClass) {
        byte[] aux = mesh.getAux();
        for (int offset = 0; offset < aux.length; offset += 4) {
            if ((aux[offset] & 0xFF) == semanticClass) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDefinedClass(ChainPreviewMesh mesh) {
        byte[] aux = mesh.getAux();
        for (int offset = 0; offset < aux.length; offset += 4) {
            if ((aux[offset] & 0xFF) != ChainPreviewSemanticClass.UNDEFINED) {
                return true;
            }
        }
        return false;
    }

    private static int distinctClasses(ChainPreviewMesh mesh) {
        Set<Integer> classes = new HashSet<Integer>();
        byte[] aux = mesh.getAux();
        for (int offset = 0; offset < aux.length; offset += 4) {
            classes.add(Integer.valueOf(aux[offset] & 0xFF));
        }
        return classes.size();
    }

    private static int appearOrder(byte[] aux, int vertex) {
        return (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
    }
}
