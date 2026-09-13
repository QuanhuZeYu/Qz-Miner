package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T31 波次 6 生产序契约（补 T27 验证网盲区：既有夹具全部按时间序喂入）。
 *
 * <p>两套判据分开：
 * ①「同序差分」——把生产真实喂入顺序（最新→最早）交给实现，期望值与实现使用**同一喂入序与锚点**
 * 的独立参考模型逐字节比对（验证差分网本身覆盖生产序）；
 * ②「需求语义」——规则 §5 用户拍板「动画保留出现顺序」+ 冻结 §D：最早目标必须 order 0、最新 order N-1，
 * 且锚点=最早目标；此判据下生产全量装配入口（T29 修正目标）当前为红。</p>
 *
 * <p>顺序无关判据统一用**世界坐标**（local + meshOrigin）比较，避免锚点不同造成的平移假差异。</p>
 */
public class ProductionOrderContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float THICKNESS = 0.045F;

    /** 时间序（最早→最新）。 */
    private static List<ChainTarget> chronological(int... xs) {
        List<ChainTarget> list = new ArrayList<ChainTarget>();
        for (int x : xs) {
            list.add(new ChainTarget(x, 0, 0));
        }
        return list;
    }

    /** 生产快照序（最新→最早）。 */
    private static List<ChainTarget> newestFirst(int... xs) {
        List<ChainTarget> list = chronological(xs);
        Collections.reverse(list);
        return list;
    }

    @Test
    public void productionSnapshotIteratesNewestFirst() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(0, 0, 0)));
        Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(3, 0, 0)));
        Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(6, 0, 0)));
        List<ChainTarget> feed = new ArrayList<ChainTarget>();
        for (ChainTarget target : state.captureRenderSnapshot().getTargets()) {
            feed.add(target);
        }
        Assert.assertEquals(3, feed.size());
        Assert.assertEquals("生产快照第 0 个必须是最新目标", 6, feed.get(0).getX());
        Assert.assertEquals(3, feed.get(1).getX());
        Assert.assertEquals("生产快照末位必须是最早目标", 0, feed.get(2).getX());
    }

    @Test
    public void sameFeedOrderDifferentialCoversProductionOrder() {
        // 72fd97e 起：公共 build 与 GenerationSession 都按「生产快照序（最新→最早）」解释输入，
        // 内部翻转为时间序装配、锚点=代内最早目标。参考模型按时间序建模（自动锚点=最早目标）。
        List<ChainTarget> production = newestFirst(0, 3, 6, 9);
        List<ChainTarget> chronological = new ArrayList<ChainTarget>(production);
        Collections.reverse(chronological);
        VerifyMeshReferenceModel.Expectation expectation =
            VerifyMeshReferenceModel.build(chronological, null, THICKNESS, false);

        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            production, VISUALS, THICKNESS, null);
        List<VerifyMeshReferenceModel.Mismatch> mismatches =
            VerifyMeshReferenceModel.compare(expectation, mesh);
        Assert.assertTrue("生产序喂入全量入口必须等价：" + mismatches, mismatches.isEmpty());

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh sessionMesh = session.extend(production, null, VISUALS, THICKNESS);
        List<VerifyMeshReferenceModel.Mismatch> sessionMismatches =
            VerifyMeshReferenceModel.compare(expectation, sessionMesh);
        Assert.assertTrue("生产序喂入代级会话必须等价：" + sessionMismatches, sessionMismatches.isEmpty());

        // 顺序敏感判据：同一喂入序下两条路径必须逐字节一致
        Assert.assertArrayEquals(mesh.vertexArray(), sessionMesh.vertexArray(), 0.0F);
        Assert.assertArrayEquals(mesh.indexArray(), sessionMesh.indexArray());
        Assert.assertArrayEquals(mesh.auxArray(), sessionMesh.auxArray());
    }

    @Test
    public void generationSessionAlreadyUsesEarliestFirstOrder() {
        // 可复现序列「先 A 后 B」：A=(0,0,0) 先出现，B=(3,0,0) 后出现
        List<ChainTarget> production = newestFirst(0, 3);
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh mesh = session.extend(production, null, VISUALS, THICKNESS);
        Assert.assertEquals("锚点必须是代内最早目标", 0, session.getAnchorX());
        assertTargetOrders("代级会话", mesh, production, 1, 0);
        assertRequiredSemantics("代级会话", mesh, production);
    }

    @Test
    public void productionFullPathMustAlsoUseEarliestFirstOrder() {
        // 72fd97e 已统一方向：公共 begin/build 按生产快照序解释输入、内部翻转为时间序装配，
        // 故全量入口同样必须「最早=0、最新=N-1」并锚定最早目标（本例为回归锁）。
        List<ChainTarget> production = newestFirst(0, 3, 6);
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            production, VISUALS, THICKNESS, null);
        assertTargetOrders("全量装配入口", mesh, production, 2, 1, 0);
        assertRequiredSemantics("全量装配入口", mesh, production);

        // 完整差分：全量装配入口按生产序喂入时也必须等于「时间序 + 锚点=最早目标」的独立模型
        List<ChainTarget> chronological = chronological(0, 3, 6);
        VerifyMeshReferenceModel.Expectation required =
            VerifyMeshReferenceModel.build(chronological, null, THICKNESS, false);
        List<VerifyMeshReferenceModel.Mismatch> mismatches =
            VerifyMeshReferenceModel.compare(required, mesh);
        Assert.assertTrue("全量装配入口必须满足生产时间序语义：" + mismatches, mismatches.isEmpty());
    }

    @Test
    public void geometryIsOrderIndependentButOrderSensitiveStreamsDiffer() {
        // 统一方向后「喂入序」即语义本身：同一语义序喂入两次必须逐字节相同；
        // 同一目标集合的相反语义序必然换锚点，但形状（按各自锚点归一化后）必须一致。
        List<ChainTarget> production = newestFirst(0, 3, 6, 9);
        List<ChainTarget> mirrored = chronological(0, 3, 6, 9);
        ChainPreviewMesh fromProduction = new ChainPreviewMeshBuilder().build(
            production, VISUALS, THICKNESS, null);
        ChainPreviewMesh sameAgain = new ChainPreviewMeshBuilder().build(
            production, VISUALS, THICKNESS, null);
        ChainPreviewMesh fromMirrored = new ChainPreviewMeshBuilder().build(
            mirrored, VISUALS, THICKNESS, null);

        Assert.assertArrayEquals("同一喂入序必须逐字节可复现",
            fromProduction.vertexArray(), sameAgain.vertexArray(), 0.0F);
        Assert.assertArrayEquals(fromProduction.auxArray(), sameAgain.auxArray());

        // 顺序敏感判据必须看「序号→目标」映射：四个同形孤立条柱的 raw aux 字节序列恰好相同，
        // 直接比 aux 数组会给出假通过。
        int[] productionMapping = targetOrders(fromProduction, production);
        Assert.assertArrayEquals("喂入序首=最新、末=最早：序号必须 0..N-1，最早=N-1",
            new int[] {3, 2, 1, 0}, productionMapping);
        int[] mirroredMapping = targetOrders(fromMirrored, mirrored);
        Assert.assertArrayEquals("相反语义序下映射形态相同（序号由喂入位置决定）",
            new int[] {3, 2, 1, 0}, mirroredMapping);

        // 顺序无关几何等价：锚点不同（0 vs 9），按各自锚点归一化后形状必须一致
        Assert.assertEquals("相反语义序必然换锚点", 0, fromProduction.getOriginX());
        Assert.assertEquals(9, fromMirrored.getOriginX());
        Assert.assertEquals(fromProduction.getBlockCount(), fromMirrored.getBlockCount());
        assertSameNormalizedPositions("归一化后顶点形状必须一致", fromProduction, fromMirrored);
        assertSameNormalizedQuads("归一化后 quad 形状必须一致", fromProduction, fromMirrored);

        // 与参考模型也必须一致：镜像喂入的语义 = 反向时间序 [9,6,3,0]，锚点=9
        List<ChainTarget> mirroredChronological = new ArrayList<ChainTarget>(mirrored);
        Collections.reverse(mirroredChronological);
        VerifyMeshReferenceModel.Expectation mirroredExpectation =
            VerifyMeshReferenceModel.build(mirroredChronological, null, THICKNESS, false);
        List<VerifyMeshReferenceModel.Mismatch> mirroredMismatches =
            VerifyMeshReferenceModel.compare(mirroredExpectation, fromMirrored);
        Assert.assertTrue("镜像喂入必须与独立参考模型等价：" + mirroredMismatches,
            mirroredMismatches.isEmpty());
    }

    /** 需求语义：最早目标 order 0、最新 order N-1、锚点=最早目标、序号致密无缺。 */
    private static void assertRequiredSemantics(String label, ChainPreviewMesh mesh,
            List<ChainTarget> productionNewestFirst) {
        List<ChainTarget> chronological = new ArrayList<ChainTarget>(productionNewestFirst);
        Collections.reverse(chronological);
        Assert.assertEquals(label + " 锚点必须是代内最早目标",
            chronological.get(0).getX(), mesh.getOriginX());
        int[] orders = targetOrders(mesh, chronological);
        for (int index = 0; index < orders.length; index++) {
            Assert.assertEquals(label + " 时间序第 " + index + " 个（" + describe(chronological.get(index))
                + "）必须 order=" + index + "（最早=0、最新=N-1）", index, orders[index]);
        }
    }

    private static void assertTargetOrders(String label, ChainPreviewMesh mesh,
            List<ChainTarget> feedOrder, int... expectedOrdersPerFeedIndex) {
        int[] orders = targetOrders(mesh, feedOrder);
        for (int index = 0; index < expectedOrdersPerFeedIndex.length; index++) {
            Assert.assertEquals(label + " 喂入序第 " + index + " 个 " + describe(feedOrder.get(index))
                + " 的 appearOrder", expectedOrdersPerFeedIndex[index], orders[index]);
        }
    }

    /** @return 与 targets 逐位对应的 appearOrder（按条柱归属取最小序号） */
    private static int[] targetOrders(ChainPreviewMesh mesh, List<ChainTarget> targets) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        Assert.assertNotNull("网格必须带 aux 流", aux);
        int[] orders = new int[targets.size()];
        Arrays.fill(orders, Integer.MAX_VALUE);
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            float worldX = mesh.getOriginX() + vertices[vertex * 3];
            float worldY = mesh.getOriginY() + vertices[vertex * 3 + 1];
            float worldZ = mesh.getOriginZ() + vertices[vertex * 3 + 2];
            int order = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            for (int index = 0; index < targets.size(); index++) {
                ChainTarget target = targets.get(index);
                if (inside(worldX, target.getX()) && inside(worldY, target.getY())
                        && inside(worldZ, target.getZ())) {
                    orders[index] = Math.min(orders[index], order);
                }
            }
        }
        for (int index = 0; index < orders.length; index++) {
            Assert.assertTrue("目标 " + describe(targets.get(index)) + " 必须至少有一个归属顶点",
                orders[index] != Integer.MAX_VALUE);
        }
        return orders;
    }

    private static String describe(ChainTarget target) {
        return "(" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
    }

    private static boolean inside(float world, int block) {
        return world >= block - 0.6F && world <= block + 1.6F;
    }

    /** 世界坐标顶点集合的容差比较（跨锚点的 float 加法可能差 1 ulp）。 */
    private static void assertSameWorldPositions(String label, ChainPreviewMesh left, ChainPreviewMesh right) {
        List<float[]> first = worldVertices(left);
        List<float[]> second = worldVertices(right);
        Assert.assertEquals(label + " 顶点数必须一致", first.size(), second.size());
        boolean[] used = new boolean[second.size()];
        for (float[] candidate : first) {
            int match = -1;
            for (int index = 0; index < second.size(); index++) {
                if (!used[index] && near(candidate, second.get(index))) {
                    match = index;
                    break;
                }
            }
            Assert.assertTrue(label + " 顶点必须一一对应：" + Arrays.toString(candidate), match >= 0);
            used[match] = true;
        }
    }

    /** quad 世界坐标集合的容差比较。 */
    private static void assertSameWorldQuads(String label, ChainPreviewMesh left, ChainPreviewMesh right) {
        List<float[][]> first = worldQuads(left);
        List<float[][]> second = worldQuads(right);
        Assert.assertEquals(label + " quad 数必须一致", first.size(), second.size());
        boolean[] used = new boolean[second.size()];
        for (float[][] candidate : first) {
            int match = -1;
            for (int index = 0; index < second.size(); index++) {
                if (!used[index] && sameQuad(candidate, second.get(index))) {
                    match = index;
                    break;
                }
            }
            Assert.assertTrue(label + " quad 必须一一对应", match >= 0);
            used[match] = true;
        }
    }

    /** 归一化（各自减去逐轴最小值）后顶点形状比较：抵消锚点平移。 */
    private static void assertSameNormalizedPositions(
            String label, ChainPreviewMesh left, ChainPreviewMesh right) {
        List<float[]> first = normalizedLocalVertices(left);
        List<float[]> second = normalizedLocalVertices(right);
        Assert.assertEquals(label + " 顶点数必须一致", first.size(), second.size());
        Assert.assertNull(label, VerifyWorldGeometry.diff(first, second, VerifyWorldGeometry.DEFAULT_TOLERANCE));
    }

    /** 归一化后 quad 形状比较：抵消锚点平移。 */
    private static void assertSameNormalizedQuads(
            String label, ChainPreviewMesh left, ChainPreviewMesh right) {
        float[] leftOffsets = localOffsets(left);
        float[] rightOffsets = localOffsets(right);
        List<float[][]> first = normalizedLocalQuads(left, leftOffsets);
        List<float[][]> second = normalizedLocalQuads(right, rightOffsets);
        Assert.assertEquals(label + " quad 数必须一致", first.size(), second.size());
        boolean[] used = new boolean[second.size()];
        for (float[][] candidate : first) {
            int match = -1;
            for (int index = 0; index < second.size(); index++) {
                if (!used[index] && sameQuad(candidate, second.get(index))) {
                    match = index;
                    break;
                }
            }
            Assert.assertTrue(label + " quad 必须一一对应", match >= 0);
            used[match] = true;
        }
    }

    private static List<float[]> normalizedLocalVertices(ChainPreviewMesh mesh) {
        float[] offsets = localOffsets(mesh);
        List<float[]> vertices = new ArrayList<float[]>();
        float[] local = mesh.getVertices();
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            vertices.add(new float[] {
                local[vertex * 3] - offsets[0],
                local[vertex * 3 + 1] - offsets[1],
                local[vertex * 3 + 2] - offsets[2]
            });
        }
        return vertices;
    }

    private static List<float[][]> normalizedLocalQuads(ChainPreviewMesh mesh, float[] offsets) {
        List<float[][]> quads = new ArrayList<float[][]>();
        float[] local = mesh.getVertices();
        int[] indices = mesh.getIndices();
        for (int quad = 0; quad + 3 < indices.length; quad += 4) {
            float[][] corners = new float[4][];
            for (int corner = 0; corner < 4; corner++) {
                int vertex = indices[quad + corner];
                corners[corner] = new float[] {
                    local[vertex * 3] - offsets[0],
                    local[vertex * 3 + 1] - offsets[1],
                    local[vertex * 3 + 2] - offsets[2]
                };
            }
            quads.add(corners);
        }
        return quads;
    }

    /** @return 局部坐标逐轴最小值（把局部集合平移到原点附近，抵消不同锚点造成的整段平移） */
    private static float[] localOffsets(ChainPreviewMesh mesh) {
        float[] local = mesh.getVertices();
        float[] offsets = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                offsets[axis] = Math.min(offsets[axis], local[vertex * 3 + axis]);
            }
        }
        return offsets;
    }

    private static boolean sameQuad(float[][] left, float[][] right) {
        boolean[] used = new boolean[4];
        for (float[] corner : left) {
            int match = -1;
            for (int index = 0; index < 4; index++) {
                if (!used[index] && near(corner, right[index])) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                return false;
            }
            used[match] = true;
        }
        return true;
    }

    private static boolean near(float[] left, float[] right) {
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(left[axis] - right[axis]) > 1.0E-4F) {
                return false;
            }
        }
        return true;
    }

    private static List<float[]> worldVertices(ChainPreviewMesh mesh) {
        List<float[]> vertices = new ArrayList<float[]>();
        float[] local = mesh.getVertices();
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            vertices.add(new float[] {
                mesh.getOriginX() + local[vertex * 3],
                mesh.getOriginY() + local[vertex * 3 + 1],
                mesh.getOriginZ() + local[vertex * 3 + 2]
            });
        }
        return vertices;
    }

    private static List<float[][]> worldQuads(ChainPreviewMesh mesh) {
        List<float[][]> quads = new ArrayList<float[][]>();
        float[] local = mesh.getVertices();
        int[] indices = mesh.getIndices();
        for (int quad = 0; quad + 3 < indices.length; quad += 4) {
            float[][] corners = new float[4][];
            for (int corner = 0; corner < 4; corner++) {
                int vertex = indices[quad + corner];
                corners[corner] = new float[] {
                    mesh.getOriginX() + local[vertex * 3],
                    mesh.getOriginY() + local[vertex * 3 + 1],
                    mesh.getOriginZ() + local[vertex * 3 + 2]
                };
            }
            quads.add(corners);
        }
        return quads;
    }

    private static Set<String> expectedWorldPositionCounts(VerifyMeshReferenceModel.Expectation expectation) {
        Set<String> positions = new TreeSet<String>();
        for (String localKey : expectation.vertices.keySet()) {
            String[] parts = localKey.split(",");
            positions.add(Float.intBitsToFloat((int) Long.parseLong(parts[0], 16)) + ","
                + Float.intBitsToFloat((int) Long.parseLong(parts[1], 16)) + ","
                + Float.intBitsToFloat((int) Long.parseLong(parts[2], 16)));
        }
        return positions;
    }

    private static String bits(float value) {
        return Integer.toHexString(Float.floatToIntBits(value));
    }

    /** 世界坐标量化到 1e-3（跨锚点的 float 加法可能差 1 ulp，顶点最小间距 0.045 远大于该粒度）。 */
    private static String quantized(float value) {
        return Long.toString(Math.round(value * 1000.0F));
    }
}