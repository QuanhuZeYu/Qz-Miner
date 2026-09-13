package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T27 波次 5 差分契约（B4.1 稳定锚点 + 26 邻域增量）。
 *
 * <p>期望值来自 {@link VerifyMeshReferenceModel} 的独立全量模型：顶点位置集合按 float 位比较、
 * quad 按 4 角点规范键成集合比较（顶点顺序无关），并单独比对 meshOrigin / 块数 / 截断 / 剔除数。</p>
 *
 * <p>本类当前覆盖「参考模型 ↔ 全量构建」的基线与防假通过强度（变异检测）、26 邻域局部性、
 * 锚点与去重语义。增量 API（task-25）落地后再补「增量 ↔ 参考模型」的等价断言。</p>
 */
public class IncrementalTopologyContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);

    private static List<ChainTarget> targets(int[][] positions) {
        List<ChainTarget> list = new ArrayList<ChainTarget>();
        for (int[] position : positions) {
            list.add(new ChainTarget(position[0], position[1], position[2]));
        }
        return list;
    }

    /** 夹具以时间序（最早→最新）表达；72fd97e 起公共 build 按生产快照序解释输入，故喂入前翻转。 */
    private static ChainPreviewMesh build(List<ChainTarget> targets) {
        return new ChainPreviewMeshBuilder().build(VerifyFeeds.snapshot(targets), VISUALS);
    }

    private static VerifyMeshReferenceModel.Expectation expect(List<ChainTarget> targets) {
        return VerifyMeshReferenceModel.build(targets, null, 0.045F, false);
    }

    private static void assertEquivalent(String label, List<ChainTarget> targets) {
        ChainPreviewMesh mesh = build(targets);
        List<VerifyMeshReferenceModel.Mismatch> mismatches = VerifyMeshReferenceModel.compare(expect(targets), mesh);
        Assert.assertTrue(label + " 独立参考模型必须与全量结果等价，差异=" + mismatches, mismatches.isEmpty());
    }

    @Test
    public void referenceModelMatchesFullBuildOnCanonicalShapes() {
        assertEquivalent("single", VerifyShapes.single(0, 0, 0));
        assertEquivalent("single_offset", VerifyShapes.single(-3, 7, 2));
        assertEquivalent("line_2", VerifyShapes.line(2));
        assertEquivalent("line_17", VerifyShapes.line(17));
        assertEquivalent("plane_4", VerifyShapes.plane(4));
        assertEquivalent("solid_3", VerifyShapes.solidCube(3));
        assertEquivalent("lshape_12", VerifyShapes.lShape(12));
        assertEquivalent("edge_contact", VerifyShapes.edgeContact());
        assertEquivalent("corner_contact", VerifyShapes.cornerContact());
        assertEquivalent("duplicates_64", VerifyShapes.duplicated(64));
        assertEquivalent("scattered_24", VerifyShapes.scatteredX(24, 3));
        assertEquivalent("scatter_lattice_24", VerifyShapes.deterministicScatter(24));
        assertEquivalent("line_64", VerifyShapes.line(64));
        assertEquivalent("plane_9", VerifyShapes.plane(9));
    }

    @Test
    public void referenceModelMatchesRandomConfigurations() {
        Random random = new Random(20260913L);
        for (int round = 0; round < 24; round++) {
            List<ChainTarget> shape = new ArrayList<ChainTarget>();
            int mode = round % 4;
            if (mode == 0) {
                // 随机游走链：大量轴向相邻，检验面隐藏
                int x = random.nextInt(7) - 3;
                int y = random.nextInt(7) - 3;
                int z = random.nextInt(7) - 3;
                shape.add(new ChainTarget(x, y, z));
                for (int step = 0; step < 40; step++) {
                    int axis = random.nextInt(3);
                    int delta = random.nextBoolean() ? 1 : -1;
                    if (axis == 0) {
                        x += delta;
                    } else if (axis == 1) {
                        y += delta;
                    } else {
                        z += delta;
                    }
                    shape.add(new ChainTarget(x, y, z));
                }
            } else if (mode == 1) {
                // 随机散点：孤立块为主，混入少量相邻
                for (int index = 0; index < 60; index++) {
                    shape.add(new ChainTarget(random.nextInt(24) - 12, random.nextInt(24) - 12,
                        random.nextInt(24) - 12));
                }
            } else if (mode == 2) {
                // 对角/边接触对：检验共享棱与凹边补回
                for (int index = 0; index < 30; index++) {
                    int x = random.nextInt(16);
                    int y = random.nextInt(16);
                    int z = random.nextInt(16);
                    shape.add(new ChainTarget(x, y, z));
                    shape.add(new ChainTarget(x + 1, y + 1, z));
                    if (index % 3 == 0) {
                        shape.add(new ChainTarget(x + 1, y, z + 1));
                    }
                }
            } else {
                // 实心簇 + 空洞：检验面/棱隐藏的组合
                int size = 3 + random.nextInt(3);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        for (int z = 0; z < size; z++) {
                            if (random.nextInt(5) == 0) {
                                continue;
                            }
                            shape.add(new ChainTarget(x, y, z));
                        }
                    }
                }
            }
            assertEquivalent("random_round_" + round, shape);
        }
    }

    /**
     * 差分强度：人为破坏网格的副本，比较器必须在对应分类上报差异。
     * 这是「防假通过」的核心——比较器若恒返回空，本条会立刻红。
     */
    @Test
    public void comparatorDetectsEveryMutationCategory() {
        List<ChainTarget> shape = VerifyShapes.line(4);
        ChainPreviewMesh mesh = build(shape);
        VerifyMeshReferenceModel.Expectation expectation = expect(shape);
        Assert.assertTrue("控制组：未变异副本必须等价",
            VerifyMeshReferenceModel.compare(expectation, mesh).isEmpty());

        // vertexArray()/indexArray() 是未裁剪的内部视图（容量可能大于实际计数），
        // 变异检测必须用 getVertices()/getIndices()/getAux() 的裁剪副本，否则会误判。
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        byte[] aux = mesh.getAux();

        Assert.assertTrue("顶点位移必须被识别",
            categories(expectation, mesh, shifted(vertices, 0, 0.001F), indices, aux)
                .contains("unexpectedVertexPosition")
                || categories(expectation, mesh, shifted(vertices, 0, 0.001F), indices, aux)
                    .contains("missingVertexPosition"));

        Assert.assertTrue("丢面必须被识别",
            categories(expectation, mesh, vertices, indices, aux, mesh.getIndexCount() - 4)
                .contains("quadCount"));

        Assert.assertTrue("原点漂移必须被识别", categories(expectation, mesh, vertices, indices, aux,
            mesh.getOriginX() + 1, mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount(),
            mesh.isTruncated(), 0).contains("origin"));

        Assert.assertTrue("块数造假必须被识别", categories(expectation, mesh, vertices, indices, aux,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount() + 1,
            mesh.isTruncated(), 0).contains("blockCount"));

        byte[] wrongOrder = aux.clone();
        int originalOrder = (aux[2] & 0xFF) | ((aux[3] & 0xFF) << 8);
        int mutatedOrder = (originalOrder + 1) & 0xFFFF;
        wrongOrder[2] = (byte) (mutatedOrder & 0xFF);
        wrongOrder[3] = (byte) ((mutatedOrder >>> 8) & 0xFF);
        Set<String> orderCategories = categories(expectation, mesh, vertices, indices, wrongOrder);
        Assert.assertTrue("出现序号造假必须被识别，实际分类=" + orderCategories,
            orderCategories.contains("auxAppearOrder"));

        byte[] wrongClass = aux.clone();
        wrongClass[0] = (byte) ((aux[0] & 0xFF) == 1 ? 2 : 1);
        Set<String> classCategories = categories(expectation, mesh, vertices, indices, wrongClass);
        Assert.assertTrue("语义类别造假必须被识别，实际分类=" + classCategories,
            classCategories.contains("auxSemanticClass"));

        boolean tubeChecked = false;
        boolean junctionChecked = false;
        byte[] wrongTube = aux.clone();
        for (int vertex = 0; vertex < aux.length / 4; vertex++) {
            int tubeEdge = aux[vertex * 4 + 1] & 0xFF;
            if (tubeEdge == 255 && !junctionChecked) {
                wrongTube[vertex * 4 + 1] = 2;
                junctionChecked = true;
            } else if (tubeEdge != 255 && !tubeChecked) {
                wrongTube[vertex * 4 + 1] = (byte) (tubeEdge == 0 ? 2 : 0);
                tubeChecked = true;
            }
        }
        Assert.assertTrue("样例必须同时含接头首写与管面首写顶点", junctionChecked && tubeChecked);
        Set<String> tubeCategories = categories(expectation, mesh, vertices, indices, wrongTube);
        Assert.assertTrue("tubeEdge 造假必须被识别，实际分类=" + tubeCategories,
            tubeCategories.contains("auxTubeEdge"));

        Assert.assertTrue("截断位造假必须被识别", categories(expectation, mesh, vertices, indices, aux,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount(),
            !mesh.isTruncated(), 0).contains("truncated"));

        Assert.assertTrue("剔除计数造假必须被识别", categories(expectation, mesh, vertices, indices, aux,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount(),
            mesh.isTruncated(), 7).contains("culledTargetCount"));
    }

    /**
     * 26 邻域局部性：新增一个目标后，发生变化的 quad 必须全部落在该目标 ±2.5 格内。
     * 这条独立于增量实现，先用全量模型固定「局部性」不变量，增量落地后按同一不变量校验。
     */
    @Test
    public void addingTargetOnlyDisturbsIts26Neighborhood() {
        List<ChainTarget> base = VerifyShapes.plane(5);
        int[][] additions = {
            {12, 0, 0}, {0, 12, 0}, {0, 0, 12},
            {12, 12, 0}, {12, 0, 12}, {0, 12, 12}, {12, 12, 12},
            {16, 0, 0}, {-12, 0, 0}, {16, 16, 16}
        };
        for (int[] addition : additions) {
            List<ChainTarget> extended = new ArrayList<ChainTarget>(base);
            extended.add(new ChainTarget(addition[0], addition[1], addition[2]));
            VerifyMeshReferenceModel.Expectation before = expect(base);
            VerifyMeshReferenceModel.Expectation after = expect(extended);
            Set<String> changed = new HashSet<String>(after.quads);
            changed.removeAll(before.quads);
            Set<String> removed = new HashSet<String>(before.quads);
            removed.removeAll(after.quads);
            Assert.assertFalse("新增目标必须改变几何（否则差分无强度）: " + addition[0] + ","
                + addition[1] + "," + addition[2], changed.isEmpty());
            for (String quad : changed) {
                assertQuadWithin(quad, addition, 2.6F, "新增");
            }
            for (String quad : removed) {
                assertQuadWithin(quad, addition, 2.6F, "移除");
            }
        }
    }

    @Test
    public void anchorAndDuplicateSemanticsAreStable() {
        // 锚点 = 迭代序中最早保留的目标；重复目标不得改变锚点与几何
        List<ChainTarget> ordered = targets(new int[][] {
            {5, 0, 0}, {0, 0, 0}, {5, 0, 0}, {5, 0, 0}
        });
        ChainPreviewMesh mesh = build(ordered);
        VerifyMeshReferenceModel.Expectation expectation = expect(ordered);
        Assert.assertEquals("锚点必须是首个 unique 目标", 5, expectation.originX);
        Assert.assertEquals(5, mesh.getOriginX());
        Assert.assertEquals(2, mesh.getBlockCount());
        Assert.assertEquals(2, expectation.visibleBlockCount);
        Assert.assertTrue(VerifyMeshReferenceModel.compare(expectation, mesh).isEmpty());

        // 去重后 appearOrder 必须致密递增（0..n-1），重复目标不占序号
        List<ChainTarget> duplicates = VerifyShapes.duplicated(32);
        ChainPreviewMesh duplicateMesh = build(duplicates);
        VerifyMeshReferenceModel.Expectation duplicateExpectation = expect(duplicates);
        Assert.assertEquals(1, duplicateExpectation.visibleBlockCount);
        Assert.assertTrue(VerifyMeshReferenceModel.compare(duplicateExpectation, duplicateMesh).isEmpty());
        byte[] aux = duplicateMesh.getAux();
        for (int vertex = 0; vertex < aux.length / 4; vertex++) {
            int order = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            Assert.assertTrue("单目标网格不得出现非 0 序号: " + order, order == 0 || order == 0xFFFF);
        }

        // 两目标链：序号必须为 {0,1} 且致密
        ChainPreviewMesh pair = build(VerifyShapes.line(2));
        byte[] pairAux = pair.getAux();
        Set<Integer> orders = new HashSet<Integer>();
        for (int vertex = 0; vertex < pairAux.length / 4; vertex++) {
            orders.add(Integer.valueOf((pairAux[vertex * 4 + 2] & 0xFF)
                | ((pairAux[vertex * 4 + 3] & 0xFF) << 8)));
        }
        Assert.assertTrue("链 2 的出现序号集合必须是 {0,1}: " + orders,
            orders.contains(Integer.valueOf(0)) && orders.contains(Integer.valueOf(1)));
        Assert.assertFalse("不得出现越界序号", orders.contains(Integer.valueOf(2)));
    }

    private static void assertQuadWithin(String quad, int[] addition, float limit, String label) {
        String[] corners = quad.split(";");
        for (String corner : corners) {
            if (corner.isEmpty()) {
                continue;
            }
            String[] parts = corner.split(",");
            for (int axis = 0; axis < 3; axis++) {
                float coordinate = Float.intBitsToFloat((int) Long.parseLong(parts[axis], 16));
                float distance = Math.abs(coordinate - addition[axis]);
                Assert.assertTrue(label + " 影响的 quad 越出 26 邻域: " + corner
                    + " 距 " + addition[axis] + " = " + distance, distance <= limit);
            }
        }
    }

    private static Set<String> categories(
            VerifyMeshReferenceModel.Expectation expectation, ChainPreviewMesh mesh,
            float[] vertices, int[] indices, byte[] aux) {
        return categories(expectation, mesh, vertices, indices, aux, mesh.getIndexCount(),
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount(),
            mesh.isTruncated(), 0);
    }

    private static Set<String> categories(
            VerifyMeshReferenceModel.Expectation expectation, ChainPreviewMesh mesh,
            float[] vertices, int[] indices, byte[] aux, int indexCount) {
        return categories(expectation, mesh, vertices, indices, aux, indexCount,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), mesh.getBlockCount(),
            mesh.isTruncated(), 0);
    }

    private static Set<String> categories(
            VerifyMeshReferenceModel.Expectation expectation, ChainPreviewMesh mesh,
            float[] vertices, int[] indices, byte[] aux, int originX, int originY, int originZ,
            int blockCount, boolean truncated, int culled) {
        return categories(expectation, mesh, vertices, indices, aux, mesh.getIndexCount(),
            originX, originY, originZ, blockCount, truncated, culled);
    }

    private static Set<String> categories(
            VerifyMeshReferenceModel.Expectation expectation, ChainPreviewMesh mesh,
            float[] vertices, int[] indices, byte[] aux, int indexCount, int originX, int originY,
            int originZ, int blockCount, boolean truncated, int culled) {
        Set<String> result = new HashSet<String>();
        for (VerifyMeshReferenceModel.Mismatch mismatch : VerifyMeshReferenceModel.compare(
                expectation, vertices, mesh.getVertexFloatCount(), indices, indexCount, aux,
                originX, originY, originZ, blockCount, truncated, culled)) {
            result.add(mismatch.category);
        }
        return result;
    }

    private static float[] shifted(float[] source, int vertex, float delta) {
        float[] copy = source.clone();
        copy[vertex * 3] += delta;
        return copy;
    }
}
