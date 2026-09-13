package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.ColorBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 接口冻结 §A：aAux 字节布局、u16 小端序、appearOrder 最小值语义与降级可观测性。
 *
 * <p>主夹具为生产主场景「相邻链」；隔离方块只作补充夹具。</p>
 */
public class ChainPreviewSemanticStreamTest {

    @Test
    public void auxStreamHasFourBytesPerVertexWithFrozenLayout() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Arrays.asList(new ChainTarget(0, 0, 0), new ChainTarget(2, 0, 0), new ChainTarget(4, 0, 0)),
            visuals());

        int vertexCount = mesh.getVertexFloatCount() / 3;
        Assert.assertEquals(3 * 64 * 3, mesh.getVertexFloatCount());
        Assert.assertTrue(mesh.isAuxAvailable());
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        Assert.assertEquals(vertexCount * ChainPreviewMesh.AUX_BYTES_PER_VERTEX, aux.length);
        Assert.assertEquals(vertexCount * 4, mesh.getAuxByteCount());
        Assert.assertNull(mesh.getAuxDegradationReason());

        for (int offset = 0; offset < aux.length; offset += 4) {
            Assert.assertEquals(ChainPreviewMesh.AUX_UNDEFINED, aux[offset] & 0xFF);
            int tubeEdge = aux[offset + 1] & 0xFF;
            Assert.assertTrue("tubeEdge 必须是 0..3 或 255", tubeEdge <= 3 || tubeEdge == 255);
        }
    }

    @Test
    public void adjacentChainJunctionOrderIsMinIncidentTargetAndTubeQuadrantsAppear() {
        List<ChainTarget> chain = production(blocks(4));
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(chain, visuals());

        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        int junctionOwned = 0;
        int quadrantOwned = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            int order = appearOrder(aux, vertex * 4);
            Assert.assertTrue(
                "appearOrder 必须落在 [0, 目标数)：vertex=" + vertex + " order=" + order,
                order >= 0 && order < chain.size());
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            if (tubeEdge == ChainPreviewMesh.AUX_UNDEFINED) {
                junctionOwned++;
                Assert.assertEquals(
                    "junction 顶点必须取 incident 目标序号最小值 vertex=" + vertex,
                    lowestIncidentOrder(vertices, vertex, chain.size()),
                    order);
            } else {
                quadrantOwned++;
                // 本轮实测 tube 首写只落在 {0,1}；断言保持值域口径（⊆ {0..3, 255}），
                // 便于 B2.3/B3.x 调整首写策略后无需改写探针。
                Assert.assertTrue("tube 横截面象限槽位必须在 0..3", tubeEdge <= 3);
            }
        }
        Assert.assertTrue("相邻链必须存在 junction 相顶点（255）", junctionOwned > 0);
        Assert.assertTrue("相邻链直通格点的 tube 相顶点必须带象限值", quadrantOwned > 0);
        assertAppearOrders(mesh, chain.size());
    }

    @Test
    public void isolatedBlockKeepsJunctionFirstWritesAndDefinedOrders() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Collections.singletonList(new ChainTarget(0, 0, 0)), visuals());

        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        int junctionOwned = 0;
        for (int offset = 0; offset < aux.length; offset += 4) {
            int tubeEdge = aux[offset + 1] & 0xFF;
            Assert.assertTrue("tubeEdge 必须是 0..3 或 255", tubeEdge <= 3 || tubeEdge == 255);
            if (tubeEdge == ChainPreviewMesh.AUX_UNDEFINED) {
                junctionOwned++;
            }
        }
        Assert.assertTrue("隔离方块必须存在 junction 相（255）顶点", junctionOwned > 0);
        assertDefinedAppearOrders(mesh);
        assertAppearOrders(mesh, 1);
    }

    @Test
    public void appearOrderUsesLittleEndianU16() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < 292; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(production(targets), visuals());

        Assert.assertTrue(assertBlockOrderBytes(mesh, 2, (byte) 0x01, (byte) 0x00) > 0);
        Assert.assertTrue(assertBlockOrderBytes(mesh, 291 * 2, (byte) 0x23, (byte) 0x01) > 0);
    }

    @Test
    public void appearOrderResetsOnNextGeneration() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh first = builder.build(blocks(4), visuals());
        Assert.assertNull(first.getAuxDegradationReason());
        assertDefinedAppearOrders(first);
        assertAppearOrders(first, 4);

        // 跨代重置：新 build session 的 appearOrder 必须重新从 0 开始。
        ChainPreviewMesh second = builder.build(blocks(2), visuals());
        assertDefinedAppearOrders(second);
        assertAppearOrders(second, 2);
    }

    @Test
    public void isolatedBlocksMapAppearOrderByCollectionIndex() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            production(Arrays.asList(
                new ChainTarget(0, 0, 0), new ChainTarget(2, 0, 0), new ChainTarget(4, 0, 0))),
            visuals());

        assertDefinedAppearOrders(mesh);
        assertAppearOrders(mesh, 3);
        assertBlockAppearOrders(mesh, 0, 0);
        assertBlockAppearOrders(mesh, 2, 1);
        assertBlockAppearOrders(mesh, 4, 2);
    }

    @Test
    public void colorOnlySessionKeepsSemanticStream() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh topology = builder.build(blocks(2), visuals());
        ColorBuildSession recolor = builder.beginRecolor(
            topology, new VisualParameters(-2.0D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.04F));
        Assert.assertTrue(recolor.advance(null));

        ChainPreviewMesh recolored = recolor.getMesh();
        Assert.assertSame(topology.vertexArray(), recolored.vertexArray());
        Assert.assertSame(topology.indexArray(), recolored.indexArray());
        Assert.assertArrayEquals(topology.getAux(), recolored.getAux());
        Assert.assertEquals(topology.getAuxByteCount(), recolored.getAuxByteCount());
    }

    @Test
    public void malformedAuxIsObservableAsDegradedWithReason() {
        long degradedBefore = ChainPreviewMesh.getAuxDegradedMeshCount();

        ChainPreviewMesh malformed = new ChainPreviewMesh(
            new float[] {0.0F, 0.0F, 0.0F},
            new float[] {0.25F, 0.9F, 1.0F, 0.8F},
            new int[] {0, 0, 0, 0},
            1,
            new byte[] {1, 2, 3});
        Assert.assertFalse(malformed.isAuxAvailable());
        Assert.assertNull(malformed.getAux());
        Assert.assertEquals(0, malformed.getAuxByteCount());
        String reason = malformed.getAuxDegradationReason();
        Assert.assertNotNull("降级原因必须可通过公开访问器观察", reason);
        Assert.assertTrue(reason.contains("3"));
        Assert.assertTrue(reason.contains("4"));
        Assert.assertEquals(degradedBefore + 1L, ChainPreviewMesh.getAuxDegradedMeshCount());

        ChainPreviewMesh valid = new ChainPreviewMesh(
            new float[] {0.0F, 0.0F, 0.0F},
            new float[] {0.25F, 0.9F, 1.0F, 0.8F},
            new int[] {0, 0, 0, 0},
            1,
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0x23, (byte) 0x01});
        Assert.assertTrue(valid.isAuxAvailable());
        Assert.assertNull(valid.getAuxDegradationReason());
        Assert.assertArrayEquals(
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0x23, (byte) 0x01},
            valid.getAux());
        Assert.assertEquals(degradedBefore + 1L, ChainPreviewMesh.getAuxDegradedMeshCount());

        Assert.assertNull(ChainPreviewMesh.EMPTY.getAuxDegradationReason());
    }

    @Test
    public void emptyTargetListProducesEmptySemanticStream() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Collections.<ChainTarget>emptyList(), visuals());

        Assert.assertTrue(mesh.isEmpty());
        Assert.assertEquals(0, mesh.getBlockCount());
        Assert.assertEquals(0, mesh.getVertexFloatCount());
        Assert.assertEquals(0, mesh.getAuxByteCount());
        Assert.assertTrue(mesh.isAuxAvailable());
        Assert.assertNull(mesh.getAuxDegradationReason());
        Assert.assertFalse(mesh.isTruncated());
    }

    /** 生产快照序（最新→最早）：公共 begin/build 入口的喂入语义（内部翻转为时间序装配）。 */
    private static List<ChainTarget> production(List<ChainTarget> chronological) {
        List<ChainTarget> reversed = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<ChainTarget> blocks(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index, 0, 0));
        }
        return targets;
    }

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.04F);
    }

    /** 顶点所属格点（立方体角点，坐标 = 格点 ± halfThickness）上所有 incident 目标序号的最小值。 */
    private static int lowestIncidentOrder(float[] vertices, int vertex, int targetCount) {
        int latticeX = Math.round(vertices[vertex * 3]);
        int latticeY = Math.round(vertices[vertex * 3 + 1]);
        int latticeZ = Math.round(vertices[vertex * 3 + 2]);
        int lowest = Integer.MAX_VALUE;
        for (int target = 0; target < targetCount; target++) {
            if (latticeX >= target && latticeX <= target + 1
                    && latticeY >= 0 && latticeY <= 1
                    && latticeZ >= 0 && latticeZ <= 1) {
                lowest = Math.min(lowest, target);
            }
        }
        return lowest;
    }

    private static void assertDefinedAppearOrders(ChainPreviewMesh mesh) {
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        for (int offset = 0; offset < aux.length; offset += 4) {
            Assert.assertTrue(
                "appearOrder 不得为未定义 0xFFFF",
                appearOrder(aux, offset) != ChainPreviewMesh.APPEAR_ORDER_UNDEFINED);
        }
    }

    private static void assertAppearOrders(ChainPreviewMesh mesh, int expectedCount) {
        Set<Integer> orders = new HashSet<Integer>();
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        for (int offset = 0; offset < aux.length; offset += 4) {
            orders.add(Integer.valueOf(appearOrder(aux, offset)));
        }
        Assert.assertEquals(expectedCount, orders.size());
        for (int order = 0; order < expectedCount; order++) {
            Assert.assertTrue("appearOrder 不得跳号：" + order, orders.contains(Integer.valueOf(order)));
        }
    }

    private static void assertBlockAppearOrders(ChainPreviewMesh mesh, int worldX, int expectedOrder) {
        Assert.assertTrue(assertBlockOrderValue(mesh, worldX, expectedOrder) > 0);
    }

    private static int assertBlockOrderValue(ChainPreviewMesh mesh, int worldX, int expectedOrder) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.03F || localX > worldX + 1.03F) {
                continue;
            }
            found++;
            Assert.assertEquals("block@" + worldX + " vertex " + vertex, expectedOrder, appearOrder(aux, vertex * 4));
        }
        return found;
    }

    private static int assertBlockOrderBytes(
            ChainPreviewMesh mesh, int worldX, byte lowByte, byte highByte) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.03F || localX > worldX + 1.03F) {
                continue;
            }
            found++;
            Assert.assertEquals(ChainPreviewMesh.AUX_UNDEFINED, aux[vertex * 4] & 0xFF);
            Assert.assertEquals("低字节", lowByte, aux[vertex * 4 + 2]);
            Assert.assertEquals("高字节", highByte, aux[vertex * 4 + 3]);
        }
        return found;
    }

    private static int appearOrder(byte[] aux, int offset) {
        return (aux[offset + 2] & 0xFF) | ((aux[offset + 3] & 0xFF) << 8);
    }
}
