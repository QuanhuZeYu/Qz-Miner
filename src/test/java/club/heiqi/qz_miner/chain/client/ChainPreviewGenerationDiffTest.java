package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
        List<List<List<ChainTarget>>> shapes = fourteenShapes();
        for (int index = 0; index < shapes.size(); index++) {
            List<List<ChainTarget>> batches = shapes.get(index);
            assertDifferential(flatten(batches), batches, "shape#" + index);
        }
        Assert.assertEquals(14, shapes.size());
    }

    /** 14 组形状夹具（lod=off 与 lod=auto 差分共用）。 */
    static List<List<List<ChainTarget>>> fourteenShapes() {
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
        return shapes;
    }

    @Test
    public void twoHundredRandomSequencesAreDifferentialEqual() {
        Random random = new Random(20260913L);
        for (int caseIndex = 0; caseIndex < 200; caseIndex++) {
            List<List<ChainTarget>> batches = randomBatches(random);
            List<ChainTarget> chronology = flatten(batches);
            Assert.assertFalse("随机序列不得为空", chronology.isEmpty());
            assertDifferential(chronology, batches, "random#" + caseIndex);
        }
    }

    // ---- B3.x lod=auto 增量差分：相机序列 + 与全量同一滞回状态机 ----

    /**
     * 相机距离序列（相机置于 {@code (0.5 - d, 0.5, 0.5)}，目标 x 中心距离 ≈ d + x）：
     * 覆盖「alpha == enter 剔除 → 死区保持 → alpha >= exit 恢复 → 再次进入」全过程。
     */
    private static final int[] LOD_CAMERA_DISTANCES = {100, 100, 96, 99, 101, 90, 103, 100, 97};
    private static final float LOD_MIN_ALPHA = 0.05F;

    private static VisualParameters lodVisuals(int cameraDistance) {
        return new VisualParameters(
            0.5D - (double) cameraDistance, 0.5D, 0.5D,
            0.0D, 100.0D, 1.0F, LOD_MIN_ALPHA, THICKNESS, true, LOD_MIN_ALPHA);
    }

    @Test
    public void fourteenShapesUnderLodCameraSequenceAreDifferentialEqual() {
        List<List<List<ChainTarget>>> shapes = fourteenShapes();
        for (int index = 0; index < shapes.size(); index++) {
            assertLodCameraSequenceDifferential(
                flatten(shapes.get(index)), shapes.get(index), "lod-shape#" + index);
        }
    }

    @Test
    public void twoHundredRandomSequencesUnderLodCameraSequenceAreDifferentialEqual() {
        Random random = new Random(20260913L);
        for (int caseIndex = 0; caseIndex < 200; caseIndex++) {
            List<List<ChainTarget>> batches = randomBatches(random);
            List<ChainTarget> chronology = flatten(batches);
            Assert.assertFalse("随机序列不得为空", chronology.isEmpty());
            assertLodCameraSequenceDifferential(chronology, batches, "lod-random#" + caseIndex);
        }
    }

    /** lod=auto <-> lod=off 交替（配置热切换）也必须逐步与全量逐字节一致。 */
    @Test
    public void lodModeSwitchSequenceIsDifferentialEqual() {
        List<List<ChainTarget>> revisionBatches = batches(line(18), 3);
        boolean[] lodModes = {true, true, false, true, false, false};
        ChainPreviewMeshBuilder sessionBuilder = new ChainPreviewMeshBuilder();
        ChainPreviewMeshBuilder referenceBuilder = new ChainPreviewMeshBuilder();
        GenerationSession session = sessionBuilder.beginGeneration();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        for (int revision = 0; revision < revisionBatches.size(); revision++) {
            accumulated.addAll(revisionBatches.get(revision));
            List<ChainTarget> chronology = uniqueOf(accumulated);
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            boolean lodEnabled = lodModes[revision % lodModes.length];
            VisualParameters visuals = lodEnabled
                ? lodVisuals(LOD_CAMERA_DISTANCES[revision % LOD_CAMERA_DISTANCES.length])
                : visuals();
            ChainPreviewMesh incremental =
                session.extend(snapshot, classesFor(snapshot), visuals, THICKNESS);
            ChainPreviewMesh reference = referenceBuilder.buildWithOrigin(
                new ArrayList<ChainTarget>(chronology), visuals, THICKNESS, classesFor(chronology),
                chronology.get(0).getX(), chronology.get(0).getY(), chronology.get(0).getZ());

            String label = "lod-switch#rev" + revision + "(lod=" + lodEnabled + ")";
            assertMeshByteEqual(label, reference, incremental);
            Assert.assertEquals(label + " 滞回记忆规模",
                referenceBuilder.getLodHysteresisMemorySize(),
                sessionBuilder.getLodHysteresisMemorySize());
        }
    }

    /**
     * lod=auto 相机序列差分：每个修订都与「同相机参数 + 同滞回记忆状态」的全量装配逐字节一致。
     *
     * <p>参考侧用**独立 builder** 保有独立但同步演化的滞回记忆（同一判定顺序 + 同一状态机），
     * 因此每个修订的记忆规模也必须一致：增量不是另算一套剔除，而是复用同一状态机的判定结果。
     * 参考目标用 unique 时间序（会话 chronology 口径），避免上游重复值影响剔除计数口径。</p>
     */
    private static void assertLodCameraSequenceDifferential(
            List<ChainTarget> chronology, List<List<ChainTarget>> revisionBatches, String label) {
        ChainPreviewMeshBuilder sessionBuilder = new ChainPreviewMeshBuilder();
        ChainPreviewMeshBuilder referenceBuilder = new ChainPreviewMeshBuilder();
        GenerationSession session = sessionBuilder.beginGeneration();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        for (int revision = 0; revision < revisionBatches.size(); revision++) {
            accumulated.addAll(revisionBatches.get(revision));
            List<ChainTarget> uniqueChronology = uniqueOf(accumulated);
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            VisualParameters visuals =
                lodVisuals(LOD_CAMERA_DISTANCES[revision % LOD_CAMERA_DISTANCES.length]);
            ChainPreviewMesh incremental =
                session.extend(snapshot, classesFor(snapshot), visuals, THICKNESS);
            if (uniqueChronology.isEmpty()) {
                // 随机批次允许为空：空快照也必须走修订路径（无参考可比）。
                Assert.assertTrue("空快照必须产出空网格", incremental.isEmpty());
                continue;
            }
            ChainPreviewMesh reference = referenceBuilder.buildWithOrigin(
                new ArrayList<ChainTarget>(uniqueChronology), visuals, THICKNESS,
                classesFor(uniqueChronology),
                uniqueChronology.get(0).getX(), uniqueChronology.get(0).getY(),
                uniqueChronology.get(0).getZ());

            String stepLabel = label + "#rev" + revision;
            Assert.assertEquals(stepLabel + " anchorX",
                uniqueChronology.get(0).getX(), session.getAnchorX());
            assertMeshByteEqual(stepLabel, reference, incremental);
            Assert.assertEquals(stepLabel + " 滞回记忆规模",
                referenceBuilder.getLodHysteresisMemorySize(),
                sessionBuilder.getLodHysteresisMemorySize());
            assertGeometryEquivalent(reference, incremental, stepLabel);
        }
        Assert.assertEquals(label + " 目标总数",
            uniqueOf(chronology).size(), session.getGenerationTargetCount());
    }

    /** 首次出现顺序去重（会话 chronology 口径）。 */
    private static List<ChainTarget> uniqueOf(List<ChainTarget> targets) {
        List<ChainTarget> unique = new ArrayList<ChainTarget>(targets.size());
        Set<Long> seen = new HashSet<Long>();
        for (ChainTarget target : targets) {
            long key = ((long) target.getX() << 42) ^ ((long) target.getY() << 21) ^ target.getZ();
            if (seen.add(Long.valueOf(key))) {
                unique.add(target);
            }
        }
        return unique;
    }

    private static List<List<ChainTarget>> randomBatches(Random random) {
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
        return batches;
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

        assertMeshByteEqual(label, reference, incremental);
        assertGeometryEquivalent(reference, incremental, label);
    }

    /** 逐字节 + plan 口径字段一致（vertices/colors/indices/aux/origin/blockCount/culled/truncated）。 */
    static void assertMeshByteEqual(
            String label, ChainPreviewMesh reference, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(label + " vertices", reference.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(label + " colors", reference.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(label + " indices", reference.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(label + " aux", reference.getAux(), actual.getAux());
        Assert.assertEquals(label + " originX", reference.getOriginX(), actual.getOriginX());
        Assert.assertEquals(label + " originY", reference.getOriginY(), actual.getOriginY());
        Assert.assertEquals(label + " originZ", reference.getOriginZ(), actual.getOriginZ());
        Assert.assertEquals(label + " blockCount", reference.getBlockCount(), actual.getBlockCount());
        Assert.assertEquals(
            label + " culled", reference.getCulledTargetCount(), actual.getCulledTargetCount());
        Assert.assertEquals(label + " truncated", reference.isTruncated(), actual.isTruncated());
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
