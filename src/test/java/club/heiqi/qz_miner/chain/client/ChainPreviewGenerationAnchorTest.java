package club.heiqi.qz_miner.chain.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B4.1 第一步：代级会话的锚点稳定 / 重锚 / 释放 与时间序 appearOrder 方向。
 *
 * <p>新锚点契约只属于 {@link GenerationSession}：meshOrigin = 代内首个目标、代内不变、
 * 超重锚距离整代重锚；既有一次性 build 入口保持原语义（另测覆盖）。</p>
 */
public class ChainPreviewGenerationAnchorTest {

    private static final float THICKNESS = 0.045F;

    @Test
    public void anchorIsGenerationFirstTargetAndStaysStableAcrossExtends() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(1, 0, 0);
        ChainTarget third = new ChainTarget(2, 0, 0);
        List<ChainTarget> snapshot1 = Arrays.asList(second, first);
        List<ChainTarget> snapshot2 = Arrays.asList(third, second, first);

        session.extend(snapshot1, classesFor(snapshot1), visuals(), THICKNESS);
        Assert.assertEquals(0, session.getAnchorX());
        Assert.assertEquals(0, session.getAnchorY());
        Assert.assertEquals(0, session.getAnchorZ());
        Assert.assertEquals(0, session.getReanchorCount());

        ChainPreviewMesh mesh = session.extend(snapshot2, classesFor(snapshot2), visuals(), THICKNESS);
        Assert.assertEquals("锚点代内必须稳定", 0, session.getAnchorX());
        Assert.assertEquals(3, session.getGenerationTargetCount());

        List<ChainTarget> chronology = Arrays.asList(first, second, third);
        ChainPreviewMesh reference = builder.buildWithOrigin(
            chronology, visuals(), THICKNESS, classesFor(chronology), 0, 0, 0);
        assertByteEqual(reference, mesh);
    }

    @Test
    public void reanchorBeyondDistanceMovesOriginAndRebuildsWholeGeneration() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration(256);
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(1, 0, 0);
        ChainTarget far = new ChainTarget(300, 0, 0);
        List<ChainTarget> snapshot1 = Arrays.asList(second, first);
        List<ChainTarget> snapshot2 = Arrays.asList(far, second, first);

        session.extend(snapshot1, classesFor(snapshot1), visuals(), THICKNESS);
        Assert.assertEquals(0, session.getAnchorX());

        ChainPreviewMesh mesh = session.extend(snapshot2, classesFor(snapshot2), visuals(), THICKNESS);
        Assert.assertEquals("超过重锚距离必须重锚", 300, session.getAnchorX());
        Assert.assertEquals(1, session.getReanchorCount());
        Assert.assertEquals(ChainPreviewMeshBuilder.DEFAULT_REANCHOR_DISTANCE, 256);

        List<ChainTarget> chronology = Arrays.asList(first, second, far);
        ChainPreviewMesh reference = builder.buildWithOrigin(
            chronology, visuals(), THICKNESS, classesFor(chronology), 300, 0, 0);
        assertByteEqual(reference, mesh);
    }

    @Test
    public void customReanchorDistanceIsConfigurable() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration(2);
        ChainTarget origin = new ChainTarget(0, 0, 0);
        List<ChainTarget> snapshot1 = Collections.singletonList(origin);
        session.extend(snapshot1, classesFor(snapshot1), visuals(), THICKNESS);
        Assert.assertEquals(0, session.getAnchorX());

        ChainTarget within = new ChainTarget(2, 0, 0);
        List<ChainTarget> snapshot2 = Arrays.asList(within, origin);
        session.extend(snapshot2, classesFor(snapshot2), visuals(), THICKNESS);
        Assert.assertEquals("Chebyshev 距离 == 阈值不得重锚", 0, session.getAnchorX());

        ChainTarget beyond = new ChainTarget(3, 0, 0);
        List<ChainTarget> snapshot3 = Arrays.asList(beyond, within, origin);
        session.extend(snapshot3, classesFor(snapshot3), visuals(), THICKNESS);
        Assert.assertEquals(3, session.getAnchorX());
        Assert.assertEquals(1, session.getReanchorCount());
        Assert.assertEquals(2, session.getReanchorDistance());
    }

    @Test
    public void disposeIsConsumedOnBuildThreadAndBlocksFurtherExtends() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainTarget target = new ChainTarget(0, 0, 0);
        List<ChainTarget> snapshot = Collections.singletonList(target);
        session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);
        Assert.assertEquals(1, session.getGenerationTargetCount());
        Assert.assertFalse(session.isDisposed());

        session.dispose();
        Assert.assertTrue(session.isDisposed());
        try {
            session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);
            Assert.fail("dispose 后必须拒绝 extend");
        } catch (IllegalStateException expected) {
            // 预期：释放请求由构建线程在 extend 入口消费
        }
        Assert.assertEquals("释放后状态必须清空", 0, session.getGenerationTargetCount());

        // 新代使用新会话：锚点与计数不得受上一代影响。
        GenerationSession next = builder.beginGeneration();
        ChainTarget nextFirst = new ChainTarget(40, 0, 0);
        List<ChainTarget> nextSnapshot = Collections.singletonList(nextFirst);
        next.extend(nextSnapshot, classesFor(nextSnapshot), visuals(), THICKNESS);
        Assert.assertEquals(40, next.getAnchorX());
        Assert.assertEquals(1, next.getGenerationTargetCount());
        Assert.assertEquals(0, next.getReanchorCount());
    }

    @Test
    public void appearOrderStartsAtEarliestTargetInTimeOrder() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(2, 0, 0);
        ChainTarget third = new ChainTarget(4, 0, 0);
        List<ChainTarget> snapshot = Arrays.asList(third, second, first);

        ChainPreviewMesh mesh = session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);

        assertBlockAppearOrder(mesh, 0, 0);
        assertBlockAppearOrder(mesh, 2, 1);
        assertBlockAppearOrder(mesh, 4, 2);
    }

    @Test
    public void semanticClassesFollowSnapshotOrderAndCountFallbacks() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(2, 0, 0);

        // 载体比快照短：越界目标按 255 兜底并计数，且不影响已对齐目标。
        ChainPreviewMesh mesh = session.extend(
            Arrays.asList(second, first),
            new int[] {ChainPreviewSemanticClass.SUB_MODE_LOCAL},
            visuals(),
            THICKNESS);

        Assert.assertEquals(1, session.getSemanticClassFallbackCount());
        Assert.assertEquals(
            ChainPreviewSemanticClass.UNDEFINED, semanticClassForBlock(mesh, 0));
        Assert.assertEquals(
            ChainPreviewSemanticClass.SUB_MODE_LOCAL, semanticClassForBlock(mesh, 2));
    }

    /** 与目标坐标绑定的确定性类别，保证增量与全量两条路径逐目标一致。 */
    private static int[] classesFor(List<ChainTarget> targets) {
        int[] values = new int[targets.size()];
        for (int index = 0; index < targets.size(); index++) {
            ChainTarget target = targets.get(index);
            values[index] = Math.floorMod(
                target.getX() + target.getY() * 3 + target.getZ() * 5, 6);
        }
        return values;
    }

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, THICKNESS);
    }

    private static void assertByteEqual(ChainPreviewMesh expected, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(expected.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(expected.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(expected.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(expected.getAux(), actual.getAux());
        Assert.assertEquals(expected.getOriginX(), actual.getOriginX());
        Assert.assertEquals(expected.getOriginY(), actual.getOriginY());
        Assert.assertEquals(expected.getOriginZ(), actual.getOriginZ());
        Assert.assertEquals(expected.getBlockCount(), actual.getBlockCount());
        Assert.assertEquals(expected.getCulledTargetCount(), actual.getCulledTargetCount());
        Assert.assertEquals(expected.isTruncated(), actual.isTruncated());
    }

    private static void assertBlockAppearOrder(ChainPreviewMesh mesh, int worldX, int expectedOrder) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.03F || localX > worldX + 1.03F) {
                continue;
            }
            found++;
            int order = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            Assert.assertEquals("block@" + worldX, expectedOrder, order);
        }
        Assert.assertTrue(found > 0);
    }

    private static int semanticClassForBlock(ChainPreviewMesh mesh, int worldX) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX >= worldX - 0.03F && localX <= worldX + 1.03F) {
                return aux[vertex * 4] & 0xFF;
            }
        }
        Assert.fail("block@" + worldX + " 顶点缺失");
        return ChainPreviewSemanticClass.UNDEFINED;
    }
}
