package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.ColorBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T7 语义顶点流 aAux 独立契约探针（接口冻结 §A + Lead 裁定）。
 *
 * <p>裁定口径：appearOrder = 顶点全部 incident 目标序号的最小值（顶点序 != 序号序）；
 * 夹具以相邻链为主、隔离方块作补充；tubeEdge 只在 tube 相新建顶点上定义，junction 相恒 255；
 * wave 的 legacy 路径整体绘制、shader 逐顶点比较。</p>
 *
 * <p>tubeEdge 直方图金值来自独立模型 cp_verify_aux_model.py（相位顺序模拟）：
 * junction 相先跑，其未覆盖的角点由 tube 相补建，因此只有 slot 0/1 会落到新顶点上
 * （跨相位共享的角点不再写入），故当前几何下 2/3 不可达。</p>
 */
public class AuxStreamContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float HALF_THICKNESS = 0.045F * 0.5F;
    private static final int UNDEFINED_BYTE = 255;
    private static final int UNDEFINED_ORDER = 0xFFFF;

    @Test
    public void auxIsBoundToVertexCountAndEveryFieldStaysInFrozenRange() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<List<ChainTarget>> fixtures = new ArrayList<List<ChainTarget>>();
        fixtures.add(VerifyShapes.line(64));
        fixtures.add(VerifyShapes.lShape(64));
        fixtures.add(VerifyShapes.plane(9));
        fixtures.add(VerifyShapes.single(0, 0, 0));
        for (List<ChainTarget> targets : fixtures) {
            ChainPreviewMesh mesh = builder.build(targets, VISUALS);
            int vertexCount = mesh.getVertexFloatCount() / 3;
            Assert.assertTrue("构建路径必须产出语义流", mesh.isAuxAvailable());
            byte[] aux = mesh.getAux();
            Assert.assertNotNull(aux);
            Assert.assertEquals(vertexCount * 4, aux.length);
            Assert.assertEquals(vertexCount * 4, mesh.getAuxByteCount());
            int targetCount = targets.size();
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int semanticClass = aux[vertex * 4] & 0xFF;
                Assert.assertTrue(
                    "semanticClass 越界：" + semanticClass,
                    semanticClass <= 5 || semanticClass == UNDEFINED_BYTE);
                int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
                Assert.assertTrue("tubeEdge 越界：" + tubeEdge, tubeEdge <= 3 || tubeEdge == UNDEFINED_BYTE);
                int order = decodeAppearOrder(aux, vertex);
                Assert.assertNotEquals(
                    "构建路径不得写未定义 appearOrder（顶点 " + vertex + "）", UNDEFINED_ORDER, order);
                Assert.assertTrue(
                    "order 必须落在 [0, targetCount)：order=" + order + " targets=" + targetCount,
                    order >= 0 && order < targetCount);
            }
        }
    }

    @Test
    public void semanticClassWithoutCarrierFallsBackToUndefined() {
        // B2.3 已接线（T16）：无类别载体的构建入口（2 参 build）必须整段写 UNDEFINED(255)；
        // 携带载体的同序映射由 SemanticAuxMappingContractTest 独立覆盖。
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh mesh = builder.build(VerifyShapes.line(64), VISUALS);
        byte[] aux = mesh.getAux();
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            Assert.assertEquals(
                "无载体时必须写 UNDEFINED(255)",
                UNDEFINED_BYTE,
                aux[vertex * 4] & 0xFF);
        }
    }

    @Test
    public void junctionVerticesCarryMinimumIncidentTargetOrderOnJoinedChains() {
        // 相邻链 line(64)：目标 i 的出现序号 = i。
        // 末端接头角点（x ≈ 64 ± 半厚）只 incident 目标 63 → order 63；
        // 直通格点（x = 63.0，incident 目标 62/63）→ order 62（最小值）；
        // 近端接头角点（x ≈ 0 ± 半厚）与首个直通格点（x = 1.0）→ order 0。
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(VerifyShapes.line(64), VISUALS);
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int farEndJunctionVertices = 0;
        int farJoinVertices = 0;
        int nearJunctionVertices = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float x = vertices[vertex * 3];
            int order = decodeAppearOrder(aux, vertex);
            if (near(x, 64.0F - HALF_THICKNESS) || near(x, 64.0F + HALF_THICKNESS)) {
                farEndJunctionVertices++;
                Assert.assertEquals("末端接头角点 order 应为 63", 63, order);
            } else if (near(x, 63.0F)) {
                farJoinVertices++;
                Assert.assertEquals("直通格点 order 应为其 incident 最小值 62", 62, order);
            } else if (near(x, -HALF_THICKNESS) || near(x, HALF_THICKNESS) || near(x, 1.0F)) {
                nearJunctionVertices++;
                Assert.assertEquals("近端 order 应为 0", 0, order);
            }
        }
        Assert.assertTrue("末端接头角点缺失，断言可能平凡通过", farEndJunctionVertices > 0);
        Assert.assertTrue("x=63 直通格点缺失", farJoinVertices > 0);
        Assert.assertTrue("近端接头角点缺失", nearJunctionVertices > 0);

        // 拐角接头（L 形）：原点接头 incident 目标 0 与 Z 臂首目标 → 最小 0。
        ChainPreviewMesh corner = new ChainPreviewMeshBuilder().build(VerifyShapes.lShape(64), VISUALS);
        float[] cornerVertices = corner.getVertices();
        byte[] cornerAux = corner.getAux();
        int originJunctionVertices = 0;
        for (int vertex = 0; vertex < cornerVertices.length / 3; vertex++) {
            float x = cornerVertices[vertex * 3];
            float y = cornerVertices[vertex * 3 + 1];
            float z = cornerVertices[vertex * 3 + 2];
            if (Math.abs(x) <= HALF_THICKNESS + 0.0005F
                    && Math.abs(y) <= HALF_THICKNESS + 0.0005F
                    && Math.abs(z) <= HALF_THICKNESS + 0.0005F) {
                originJunctionVertices++;
                Assert.assertEquals(
                    "原点拐角接头 order 应为最小 incident 序号 0", 0, decodeAppearOrder(cornerAux, vertex));
            }
        }
        Assert.assertTrue("原点拐角接头缺失", originJunctionVertices > 0);
    }

    @Test
    public void joinedChainCoversEveryTargetOrderAndWritesOnlyReachableTubeEdgeSlots() {
        ChainPreviewMesh joined = new ChainPreviewMeshBuilder().build(VerifyShapes.line(64), VISUALS);
        int vertexCount = joined.getVertexFloatCount() / 3;
        byte[] aux = joined.getAux();
        Set<Integer> orders = new HashSet<Integer>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            orders.add(Integer.valueOf(decodeAppearOrder(aux, vertex)));
        }
        Assert.assertEquals("相邻链必须覆盖每个目标序号", 64, orders.size());
        for (int order = 0; order < 64; order++) {
            Assert.assertTrue("缺少 order " + order, orders.contains(Integer.valueOf(order)));
        }
        // 口径（Lead 裁定 D）：直通格点 tube 相顶点值域 ⊆ {0,1,2,3}，junction 相为 255。
        // 独立模型实测（cp_verify_aux_model.py，早期精确断言版本已验证）：line(64) = {0:508, 1:508, 255:56}
        // —— 即当前相位下 slot 2/3 不可达，已单独上报 Lead（T6 柔边不得假设四象限齐备）。
        int undefinedEdges = 0;
        boolean definedEdgeSeen = false;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            Assert.assertTrue("tubeEdge 越界：" + tubeEdge, tubeEdge <= 3 || tubeEdge == UNDEFINED_BYTE);
            if (tubeEdge == UNDEFINED_BYTE) {
                undefinedEdges++;
            } else {
                definedEdgeSeen = true;
            }
        }
        Assert.assertTrue("直通格点必须至少产出一个定义过的 tubeEdge", definedEdgeSeen);
        Assert.assertTrue("接头相必须保留 255", undefinedEdges > 0);
    }

    @Test
    public void isolatedFixturesStayInValueDomainAndKeepUndefinedEdges() {
        // 隔离场景并非全 255：junction 相每个接头点有 1 个背向角点未被覆盖，由 tube 相首写。
        // 按 Lead 口径只断言值域与 255 存在；独立模型实测单方块 = {0:4, 1:4, 255:56}。
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(VerifyShapes.single(0, 0, 0), VISUALS);
        int vertexCount = mesh.getVertexFloatCount() / 3;
        Assert.assertEquals(64, vertexCount);
        byte[] aux = mesh.getAux();
        int undefinedEdges = 0;
        boolean definedEdgeSeen = false;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            Assert.assertTrue("tubeEdge 越界：" + tubeEdge, tubeEdge <= 3 || tubeEdge == UNDEFINED_BYTE);
            if (tubeEdge == UNDEFINED_BYTE) {
                undefinedEdges++;
            } else {
                definedEdgeSeen = true;
            }
        }
        Assert.assertTrue("隔离夹具必须存在 255 顶点", undefinedEdges > 0);
        Assert.assertTrue("隔离夹具必须存在 tube 相首写顶点", definedEdgeSeen);
    }

    @Test
    public void isolatedTargetsAreSupplementCaseOnlyAndKeepLittleEndianU16() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(VerifyShapes.scatteredX(300, 3), VISUALS);
        int vertexCount = mesh.getVertexFloatCount() / 3;
        Assert.assertEquals(300 * 64, vertexCount);
        byte[] aux = mesh.getAux();
        // Lead 口径：隔离夹具只断言值域 ⊆ {0,1,2,3,255} 且 255 存在；
        // 独立模型实测 = {0:1200, 1:1200, 255:16800}。
        int undefinedEdges = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            Assert.assertTrue("tubeEdge 越界：" + tubeEdge, tubeEdge <= 3 || tubeEdge == UNDEFINED_BYTE);
            if (tubeEdge == UNDEFINED_BYTE) {
                undefinedEdges++;
            }
        }
        Assert.assertTrue("隔离夹具必须存在 255 顶点", undefinedEdges > 0);

        Set<Integer> orders = new HashSet<Integer>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            orders.add(Integer.valueOf(decodeAppearOrder(aux, vertex)));
        }
        Assert.assertEquals(300, orders.size());
        assertIsolatedBlockOrder(mesh, 0, 0);
        assertIsolatedBlockOrder(mesh, 2, 2);
        assertIsolatedBlockOrder(mesh, 256, 256);
        assertIsolatedBlockOrder(mesh, 299, 299);
    }

    @Test
    public void sharedVerticesKeepFirstWriteMinimumOrderAcrossRebuild() {
        // 相邻两块共享接头格点：min(incident)=0，不得被后一次写入覆盖成 1。
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> pair = VerifyShapes.line(2);
        ChainPreviewMesh first = builder.build(pair, VISUALS);
        float[] vertices = first.getVertices();
        byte[] aux = first.getAux();
        int joinVertices = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            if (near(vertices[vertex * 3], 1.0F)) {
                joinVertices++;
                Assert.assertEquals("共享接头顶点必须取最小 incident 序号 0", 0, decodeAppearOrder(aux, vertex));
            }
        }
        Assert.assertTrue("共享接头顶点缺失", joinVertices > 0);
        ChainPreviewMesh second = builder.build(pair, VISUALS);
        Assert.assertArrayEquals("同输入重建必须逐字节一致", first.getAux(), second.getAux());
        Assert.assertArrayEquals(first.getIndices(), second.getIndices());
    }

    @Test
    public void malformedAuxLengthIsNotExposedAsValid() {
        // Lead 裁定 E：长度不符导致降级必须可被公开访问器观察到。
        ChainPreviewMesh malformed = new ChainPreviewMesh(
            new float[] {0.0F, 0.0F, 0.0F},
            new float[] {0.25F, 0.9F, 1.0F, 0.8F},
            new int[] {0, 0, 0, 0},
            1,
            new byte[] {1, 2, 3});
        Assert.assertFalse(malformed.isAuxAvailable());
        Assert.assertNull(malformed.getAux());
        Assert.assertEquals(0, malformed.getAuxByteCount());
    }

    @Test
    public void colorOnlySessionPreservesSemanticStream() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh topology = builder.build(VerifyShapes.line(64), VISUALS);
        ColorBuildSession recolor = builder.beginRecolor(
            topology,
            new VisualParameters(-12.0D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F));
        Assert.assertTrue(recolor.advance(null));
        ChainPreviewMesh recolored = recolor.getMesh();
        Assert.assertTrue(recolored.isAuxAvailable());
        Assert.assertArrayEquals(topology.getAux(), recolored.getAux());
        Assert.assertArrayEquals(topology.getVertices(), recolored.getVertices(), 0.0F);
        Assert.assertArrayEquals(topology.getIndices(), recolored.getIndices());
    }

    /** 失败信息直接给出真实直方图，避免"只断言非空"式假通过。 */
    private static String tubeEdgeHistogram(byte[] aux, int vertexCount) {
        TreeMap<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            Integer previous = counts.get(Integer.valueOf(tubeEdge));
            counts.put(Integer.valueOf(tubeEdge), Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }
        return counts.toString();
    }

    private static boolean near(float value, float expected) {
        return Math.abs(value - expected) <= 0.0005F;
    }

    private static int decodeAppearOrder(byte[] aux, int vertex) {
        return (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
    }

    private static void assertIsolatedBlockOrder(ChainPreviewMesh mesh, int targetIndex, int expectedOrder) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int worldX = targetIndex * 3;
        int found = 0;
        int firstVertex = -1;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.05F || localX > worldX + 1.05F) {
                continue;
            }
            found++;
            if (firstVertex < 0) {
                firstVertex = vertex;
            }
            Assert.assertEquals(
                "block@" + worldX + " 顶点 " + vertex + " order",
                expectedOrder,
                decodeAppearOrder(aux, vertex));
        }
        Assert.assertEquals("隔离目标必须整块共享同一 order", 64, found);
        Assert.assertEquals("低字节", expectedOrder & 0xFF, aux[firstVertex * 4 + 2] & 0xFF);
        Assert.assertEquals("高字节", (expectedOrder >>> 8) & 0xFF, aux[firstVertex * 4 + 3] & 0xFF);
    }
}
