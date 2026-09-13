package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B4.1 第一步差分基线：代级增量视图（时间序装配 + 稳定锚点）必须与同时间序一次性全量装配
 * 逐字节一致（vertices/colors/indices/aux/origin/blockCount/culledTargetCount/truncated），
 * 并额外断言顺序无关的几何等价（顶点世界坐标集合 + quad 多重集）。
 *
 * <p>夹具：14 组形状 + 200 组随机批次序列（固定 seed，可复现）。</p>
 */
public class ChainPreviewGenerationDiffTest {

    private static final float THICKNESS = 0.045F;

    @Test
    public void fourteenShapesAreDifferentialEqual() {
        List<List<List<ChainTarget>>> shapes = new ArrayList<List<List<ChainTarget>>>();
        shapes.add(batches(shape(new int[][] {{0, 0, 0}}), 1));
        shapes.add(batches(line(8), 3));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {3, 0, 0},
            {0, 0, 1}, {0, 0, 2}, {0, 0, 3}}), 2));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {2, 0, 0},
            {0, 1, 0}, {1, 1, 0}, {2, 1, 0},
            {0, 2, 0}, {1, 2, 0}, {2, 2, 0}}), 4));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}}), 3));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}), 2));
        shapes.add(batches(shape(new int[][] {{0, 0, 0}, {1, 1, 0}, {2, 2, 0}, {3, 3, 0}}), 2));
        shapes.add(batches(shape(new int[][] {{0, 0, 0}, {1, 1, 1}, {2, 2, 2}}), 3));
        shapes.add(batches(ring(4), 3));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {3, 0, 0}, {4, 0, 0},
            {2, 1, 0}, {2, 2, 0}}), 2));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {2, 0, 0},
            {0, 0, 1}, {2, 0, 1},
            {0, 0, 2}, {1, 0, 2}, {2, 0, 2}}), 3));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {2, 1, 1}}), 2));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {3, 0, 0}, {6, 0, 0}, {9, 0, 0}}), 2));
        shapes.add(batches(shape(new int[][] {
            {0, 0, 0}, {0, 0, 0}, {1, 0, 0}, {0, 0, 0}, {1, 0, 0}, {2, 0, 0}}), 2));

        for (int index = 0; index < shapes.size(); index++) {
            List<List<ChainTarget>> batches = shapes.get(index);
            assertDifferential(flatten(batches), batches, "shape#" + index);
        }
        Assert.assertEquals(14, shapes.size());
    }

    @Test
    public void twoHundredRandomSequencesAreDifferentialEqual() {
        Random random = new Random(20260913L);
        for (int caseIndex = 0; caseIndex < 200; caseIndex++) {
            List<List<ChainTarget>> batches = new ArrayList<List<ChainTarget>>();
            int batchCount = 2 + random.nextInt(5);
            for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
                int size = random.nextInt(9);
                List<ChainTarget> batch = new ArrayList<ChainTarget>(size);
                for (int targetIndex = 0; targetIndex < size; targetIndex++) {
                    batch.add(new ChainTarget(
                        random.nextInt(13) - 6,
                        random.nextInt(9) - 4,
                        random.nextInt(13) - 6));
                }
                batches.add(batch);
            }
            List<ChainTarget> chronology = flatten(batches);
            Assert.assertFalse("随机序列不得为空", chronology.isEmpty());
            assertDifferential(chronology, batches, "random#" + caseIndex);
        }
    }

    /** 增量视图（分批 extend）vs 全量视图（同时间序一次性装配）逐字节 + 几何等价。 */
    private static void assertDifferential(
            List<ChainTarget> chronology, List<List<ChainTarget>> batches, String label) {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        VisualParameters visuals = visuals();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        ChainPreviewMesh incremental = null;
        for (List<ChainTarget> batch : batches) {
            accumulated.addAll(batch);
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            incremental = session.extend(snapshot, classesFor(snapshot), visuals, THICKNESS);
        }
        Assert.assertNotNull(label, incremental);

        // 参考侧独立决定锚点（时间序首元素 = 代内首个目标），不复用被测会话的锚点；
        // 被测会话的锚点另行断言，避免「参考复用被测量」造成的假等价。
        int anchorX = chronology.isEmpty() ? 0 : chronology.get(0).getX();
        int anchorY = chronology.isEmpty() ? 0 : chronology.get(0).getY();
        int anchorZ = chronology.isEmpty() ? 0 : chronology.get(0).getZ();
        Assert.assertEquals(label + " anchorX", anchorX, session.getAnchorX());
        Assert.assertEquals(label + " anchorY", anchorY, session.getAnchorY());
        Assert.assertEquals(label + " anchorZ", anchorZ, session.getAnchorZ());
        ChainPreviewMesh reference = builder.buildWithOrigin(
            chronology, visuals, THICKNESS, classesFor(chronology), anchorX, anchorY, anchorZ);

        Assert.assertArrayEquals(label + " vertices", reference.getVertices(), incremental.getVertices(), 0.0F);
        Assert.assertArrayEquals(label + " colors", reference.getColors(), incremental.getColors(), 0.0F);
        Assert.assertArrayEquals(label + " indices", reference.getIndices(), incremental.getIndices());
        Assert.assertArrayEquals(label + " aux", reference.getAux(), incremental.getAux());
        Assert.assertEquals(label + " originX", reference.getOriginX(), incremental.getOriginX());
        Assert.assertEquals(label + " originY", reference.getOriginY(), incremental.getOriginY());
        Assert.assertEquals(label + " originZ", reference.getOriginZ(), incremental.getOriginZ());
        Assert.assertEquals(label + " blockCount", reference.getBlockCount(), incremental.getBlockCount());
        Assert.assertEquals(
            label + " culled", reference.getCulledTargetCount(), incremental.getCulledTargetCount());
        Assert.assertEquals(label + " truncated", reference.isTruncated(), incremental.isTruncated());
        assertGeometryEquivalent(reference, incremental, label);
    }

    /** 顺序无关几何等价：顶点世界坐标多重集 + quad 多重集（顶点重映射后规范化）。 */
    private static void assertGeometryEquivalent(
            ChainPreviewMesh expected, ChainPreviewMesh actual, String label) {
        Assert.assertEquals(label + " indexCount", expected.getIndexCount(), actual.getIndexCount());
        List<String> expectedKeys = worldVertexKeys(expected);
        List<String> actualKeys = worldVertexKeys(actual);
        Assert.assertEquals(label + " vertexSet", expectedKeys, actualKeys);

        Map<String, Integer> ranks = new HashMap<String, Integer>();
        for (int index = 0; index < expectedKeys.size(); index++) {
            ranks.put(expectedKeys.get(index), Integer.valueOf(index));
        }
        Assert.assertEquals(label + " quadSet", quadKeys(expected, ranks), quadKeys(actual, ranks));
    }

    private static List<String> worldVertexKeys(ChainPreviewMesh mesh) {
        float[] vertices = mesh.getVertices();
        int count = mesh.getVertexFloatCount() / 3;
        List<String> keys = new ArrayList<String>(count);
        for (int vertex = 0; vertex < count; vertex++) {
            keys.add(worldKey(mesh, vertices, vertex));
        }
        Collections.sort(keys);
        return keys;
    }

    private static List<String> quadKeys(ChainPreviewMesh mesh, Map<String, Integer> ranks) {
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        int vertexCount = mesh.getVertexFloatCount() / 3;
        List<String> quads = new ArrayList<String>(indices.length / 4);
        for (int offset = 0; offset < indices.length; offset += 4) {
            String[] corners = new String[4];
            for (int corner = 0; corner < 4; corner++) {
                int index = indices[offset + corner];
                Assert.assertTrue(index >= 0 && index < vertexCount);
                Integer rank = ranks.get(worldKey(mesh, vertices, index));
                Assert.assertNotNull("顶点必须在参考集合中", rank);
                corners[corner] = String.valueOf(rank);
            }
            Arrays.sort(corners);
            quads.add(Arrays.toString(corners));
        }
        Collections.sort(quads);
        return quads;
    }

    private static String worldKey(ChainPreviewMesh mesh, float[] vertices, int vertex) {
        float x = vertices[vertex * 3] + mesh.getOriginX();
        float y = vertices[vertex * 3 + 1] + mesh.getOriginY();
        float z = vertices[vertex * 3 + 2] + mesh.getOriginZ();
        return bits(x) + ":" + bits(y) + ":" + bits(z);
    }

    private static int bits(float value) {
        return Float.floatToIntBits(value == 0.0F ? 0.0F : value);
    }

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

    private static List<ChainTarget> line(int count) {
        int[][] coords = new int[count][];
        for (int index = 0; index < count; index++) {
            coords[index] = new int[] {index, 0, 0};
        }
        return shape(coords);
    }

    private static List<ChainTarget> ring(int size) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < size; index++) {
            targets.add(new ChainTarget(index, 0, 0));
        }
        for (int index = 1; index < size; index++) {
            targets.add(new ChainTarget(size - 1, index, 0));
        }
        for (int index = size - 2; index >= 0; index--) {
            targets.add(new ChainTarget(index, size - 1, 0));
        }
        for (int index = size - 2; index >= 1; index--) {
            targets.add(new ChainTarget(0, index, 0));
        }
        return targets;
    }

    private static List<ChainTarget> shape(int[][] coords) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(coords.length);
        for (int[] coordinate : coords) {
            targets.add(new ChainTarget(coordinate[0], coordinate[1], coordinate[2]));
        }
        return targets;
    }

    /** 把时间序列表按固定大小切成批（每批为「代内新增」的连续段）。 */
    private static List<List<ChainTarget>> batches(List<ChainTarget> chronology, int batchSize) {
        List<List<ChainTarget>> result = new ArrayList<List<ChainTarget>>();
        for (int start = 0; start < chronology.size(); start += batchSize) {
            result.add(new ArrayList<ChainTarget>(
                chronology.subList(start, Math.min(chronology.size(), start + batchSize))));
        }
        return result;
    }

    private static List<ChainTarget> flatten(List<List<ChainTarget>> batches) {
        List<ChainTarget> result = new ArrayList<ChainTarget>();
        for (List<ChainTarget> batch : batches) {
            result.addAll(batch);
        }
        return result;
    }
}
