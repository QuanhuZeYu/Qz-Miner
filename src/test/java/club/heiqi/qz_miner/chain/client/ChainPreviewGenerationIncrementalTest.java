package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B4.1 第二步（阶段 A）补充探针：
 * 1) 按**生产快照序（最新→最早）**喂入的差分——现有探针虽然快照也是最新→最早，
 *    但本类显式模拟「生产修订：新目标在天部」并在每一步比对参考，另用方向敏感断言防止方向被忽略；
 * 2) 代级增量与逐次全量重建的 headless 计时对比（数量级，真机待验证）。
 */
public class ChainPreviewGenerationIncrementalTest {

    private static final float THICKNESS = 0.045F;

    @Test
    public void productionSnapshotOrderMatchesReferenceAtEveryRevision() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();

        // 生产模式：每次修订都是「最新→最早」的完整快照，新目标加在头部。
        for (int x = 0; x < 8; x++) {
            accumulated.add(new ChainTarget(x, 0, 0));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);   // 最新 -> 最早
            ChainPreviewMesh incremental =
                session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);

            ChainPreviewMesh reference = builder.buildWithOrigin(
                accumulated, visuals(), THICKNESS, classesFor(accumulated),
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            assertByteEqual("rev_" + x, reference, incremental);
        }

        ChainPreviewMesh mesh = session.getMesh();
        Assert.assertEquals("锚点必须是代内首个（最早）目标", 0, session.getAnchorX());
        Assert.assertEquals(8, session.getGenerationTargetCount());
        // 方向：最早目标 order 0；最新目标必须携带最大序号（共享直通格点取 min incident，故按存在性断言）。
        assertBlockAppearOrder(mesh, 0, 0);
        Assert.assertTrue("最新目标必须产生序号 7", containsOrder(mesh, 7));
        Assert.assertFalse("不得出现越界序号", containsOrder(mesh, 8));
    }

    @Test
    public void reversedSnapshotOrderIsObservablyDifferentToCatchDirectionBias() {
        List<ChainTarget> chronological = new ArrayList<ChainTarget>();
        for (int x = 0; x < 4; x++) {
            chronological.add(new ChainTarget(x, 0, 0));
        }
        List<ChainTarget> productionSnapshot = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(productionSnapshot);

        GenerationSession correct = new ChainPreviewMeshBuilder().beginGeneration();
        correct.extend(productionSnapshot, classesFor(productionSnapshot), visuals(), THICKNESS);
        Assert.assertEquals("生产序下锚点 = 最早目标", 0, correct.getAnchorX());

        // 若把时间序误当作快照序喂入，锚点会变成「实际最新」目标 -> 方向敏感（防止方向被忽略）。
        GenerationSession wrongOrder = new ChainPreviewMeshBuilder().beginGeneration();
        wrongOrder.extend(chronological, classesFor(chronological), visuals(), THICKNESS);
        Assert.assertEquals("方向必须被真实消费：误序下锚点不同", 3, wrongOrder.getAnchorX());
    }

    /**
     * 可分片、可续跑的生产形态：大网格 beginRevision + 逐片 advance（gate 让出）必须能续跑，
     * 最终结果与一次性 extend 逐字节等价；gate 恒不让出时一次完成。
     */
    @Test
    public void chunkedRevisionYieldsAndResumesByteIdenticalToOneShot() {
        List<ChainTarget> chronology = line(240);
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronology);
        Collections.reverse(snapshot);
        int[] classes = classesFor(snapshot);

        ChainPreviewMesh oneShot = new ChainPreviewMeshBuilder().beginGeneration()
            .extend(snapshot, classes, visuals(), THICKNESS);

        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainPreviewMeshBuilder.MeshBuildSession revision =
            session.beginRevision(snapshot, classes, visuals(), THICKNESS);
        int yields = 0;
        boolean complete = false;
        while (!complete) {
            complete = revision.advance(new ChainPreviewMeshBuilder.WorkGate() {
                private int checks;

                @Override
                public boolean shouldYield() {
                    return ++checks > 3;
                }
            });
            if (!complete) {
                yields++;
            }
            Assert.assertTrue("让出次数必须有限，实际=" + yields, yields < 500_000);
        }
        ChainPreviewMesh chunked = revision.getMesh();
        System.out.println("[t29-yield] yields=" + yields + " vertices=" + chunked.getVertexFloatCount());
        Assert.assertTrue("大网格必须发生多次让出，实际=" + yields, yields > 0);
        assertByteEqual("chunked", oneShot, chunked);

        // gate 恒不让出（null）：一次完成，且与非增量路径语义一致。
        ChainPreviewMeshBuilder immediateBuilder = new ChainPreviewMeshBuilder();
        GenerationSession immediateSession = immediateBuilder.beginGeneration();
        ChainPreviewMeshBuilder.MeshBuildSession immediate =
            immediateSession.beginRevision(snapshot, classes, visuals(), THICKNESS);
        Assert.assertTrue(immediate.advance(null));
        assertByteEqual("immediate", oneShot, immediate.getMesh());
    }

    /**
     * 生产生命周期契约（阶段 B 冻结口径）：代内复用同一会话；世代变化 = dispose 旧会话 + 新建；
     * 新代锚点/序号从零开始，不携带上一代状态。
     */
    @Test
    public void productionLifecycleReusesSessionWithinGenerationAndResetsAcross() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        for (int x = 0; x < 3; x++) {
            accumulated.add(new ChainTarget(x, 0, 0));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);
        }
        Assert.assertEquals("代内复用同一会话：锚点=首个目标", 0, session.getAnchorX());
        Assert.assertEquals(3, session.getGenerationTargetCount());

        // 世代变化：dispose 旧会话（构建线程下次 extend 消费），新建会话。
        session.dispose();
        try {
            session.extend(Collections.<ChainTarget>emptyList(), null, visuals(), THICKNESS);
            Assert.fail("已释放会话必须拒绝 extend");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        GenerationSession nextGeneration = builder.beginGeneration();
        List<ChainTarget> nextTargets = Arrays.asList(new ChainTarget(20, 0, 0));
        List<ChainTarget> nextSnapshot = new ArrayList<ChainTarget>(nextTargets);
        Collections.reverse(nextSnapshot);
        ChainPreviewMesh nextMesh =
            nextGeneration.extend(nextSnapshot, classesFor(nextSnapshot), visuals(), THICKNESS);
        Assert.assertEquals("新代锚点必须来自新代首个目标", 20, nextGeneration.getAnchorX());
        Assert.assertEquals(0, nextGeneration.getReanchorCount());
        Assert.assertEquals(1, nextMesh.getBlockCount());
    }

    /**
     * headless 计时（数量级，真机待验证）：同样目标的逐修订构建，代级增量 vs 逐次全量重建。
     *
     * <p>只断言「增量不慢于全量」（保守判据），数值本身用于汇报数量级，不作为验收判据。</p>
     */
    @Test
    public void headlessTimingIncrementalVersusFullRebuild() {
        measure(200, 20);   // JIT 预热
        long[] result = measure(600, 20);
        System.out.println("[t29-timing] incremental=" + result[0] + "ns full=" + result[1]
            + "ns ratio=" + ((double) result[0] / Math.max(1L, result[1])));
        Assert.assertTrue(
            "代级增量不应慢于逐次全量重建: incremental=" + result[0] + "ns full=" + result[1] + "ns",
            result[0] <= result[1]);
    }

    /**
     * B3.x lod=auto 下的增量收益（headless 计时，真机待验证）：同一目标集 + 相机缓慢推进
     * （每修订只有阈值附近的少数目标翻转包含状态），代级增量 vs 同参数全量重建。
     *
     * <p>子场景登记：</p>
     * <ul>
     *   <li>边界翻转少（相机缓慢推进）：只重算「包含状态变化 + 26 邻域 + 新增槽位」的可见段，
     *       收益来自跳过阈值以外全部槽位的邻域扫描；装配发射仍 O(网格)（与 lod=off 同一边界）。</li>
     *   <li>剔除集合整体翻转（相机瞬移跨越阈值）：脏槽位接近全部，增量退化到 ≈ 全量（
     *       {@link #headlessTimingLodThresholdFlipIsNearFullRebuild()} 实测并打印）。</li>
     *   <li>超容量快照（缓存未覆盖全部 unique 目标）：仍走既有全量回退（登记子场景）。</li>
     * </ul>
     */
    @Test
    public void headlessTimingLodIncrementalVersusFullRebuild() {
        measureLod(120, 20);
        long[] result = measureLod(400, 20);
        System.out.println("[t38-timing] lod incremental=" + result[0] + "ns full=" + result[1]
            + "ns ratio=" + ((double) result[0] / Math.max(1L, result[1])));
        Assert.assertTrue("lod=auto 增量不应慢于全量重建: incremental=" + result[0]
            + "ns full=" + result[1] + "ns", result[0] <= result[1]);
    }

    /** 相机瞬移使大量槽位包含状态翻转：增量退化到接近全量（登记，仅打印数量级）。 */
    @Test
    public void headlessTimingLodThresholdFlipIsNearFullRebuild() {
        long[] result = measureLodFlip(400, 40);
        System.out.println("[t38-timing-flip] incremental=" + result[0] + "ns full=" + result[1]
            + "ns ratio=" + ((double) result[0] / Math.max(1L, result[1])));
        Assert.assertTrue("翻转场景不得出现明显回退（<3x 全量）: incremental=" + result[0]
            + "ns full=" + result[1] + "ns", result[0] < 3L * Math.max(1L, result[1]));
    }

    /**
     * 相机缓慢推进（每修订推进 1 格）：目标 x=0..(total-1)，fadeEnd=1000，
     * 相机距 x=0 约 700 → 阈值边界落在目标链中段，每修订只有少数目标翻转。
     */
    private static long[] measureLod(int total, int batchSize) {
        List<ChainTarget> all = line(total);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainPreviewMeshBuilder referenceBuilder = new ChainPreviewMeshBuilder();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        long incrementalNanos = 0L;
        long fullNanos = 0L;
        int revision = 0;
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(total, start + batchSize);
            accumulated.addAll(all.subList(start, end));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            // 每修订推进 4 格：跨越「alpha >= exit」恢复阈值，边界附近少数目标翻转包含状态。
            VisualParameters visuals = lodVisuals(700 - revision * 4);
            int[] snapshotClasses = classesFor(snapshot);

            long incrementalStart = System.nanoTime();
            session.extend(snapshot, snapshotClasses, visuals, THICKNESS);
            long incrementalEnd = System.nanoTime();
            incrementalNanos += incrementalEnd - incrementalStart;

            int[] referenceClasses = classesFor(accumulated);
            long fullStart = System.nanoTime();
            referenceBuilder.buildWithOrigin(
                new ArrayList<ChainTarget>(accumulated), visuals, THICKNESS, referenceClasses,
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            long fullEnd = System.nanoTime();
            fullNanos += fullEnd - fullStart;
            revision++;
        }
        return new long[] {incrementalNanos, fullNanos};
    }

    /** 相机瞬移（700 <-> 1000）：包含状态整体翻转，脏槽位接近全部。 */
    private static long[] measureLodFlip(int total, int batchSize) {
        List<ChainTarget> all = line(total);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainPreviewMeshBuilder referenceBuilder = new ChainPreviewMeshBuilder();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        long incrementalNanos = 0L;
        long fullNanos = 0L;
        int revision = 0;
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(total, start + batchSize);
            accumulated.addAll(all.subList(start, end));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            VisualParameters visuals = lodVisuals((revision % 2 == 0) ? 700 : 1000);
            int[] snapshotClasses = classesFor(snapshot);

            long incrementalStart = System.nanoTime();
            session.extend(snapshot, snapshotClasses, visuals, THICKNESS);
            long incrementalEnd = System.nanoTime();
            incrementalNanos += incrementalEnd - incrementalStart;

            int[] referenceClasses = classesFor(accumulated);
            long fullStart = System.nanoTime();
            referenceBuilder.buildWithOrigin(
                new ArrayList<ChainTarget>(accumulated), visuals, THICKNESS, referenceClasses,
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            long fullEnd = System.nanoTime();
            fullNanos += fullEnd - fullStart;
            revision++;
        }
        return new long[] {incrementalNanos, fullNanos};
    }

    /** lod=auto 视觉参数：fadeEnd=1000 / enter=0.05 / exit=0.10，相机沿 -X 侧。 */
    private static VisualParameters lodVisuals(int cameraDistance) {
        return new VisualParameters(
            0.5D - (double) cameraDistance, 0.5D, 0.5D,
            0.0D, 1000.0D, 1.0F, 0.05F, THICKNESS, true, 0.05F);
    }

    private static long[] measure(int total, int batchSize) {
        List<ChainTarget> all = line(total);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        List<ChainTarget> accumulated = new ArrayList<ChainTarget>();
        long incrementalNanos = 0L;
        long fullNanos = 0L;
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(total, start + batchSize);
            accumulated.addAll(all.subList(start, end));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            List<ChainTarget> chronology = new ArrayList<ChainTarget>(accumulated);

            long incrementalStart = System.nanoTime();
            session.extend(snapshot, classesFor(snapshot), visuals(), THICKNESS);
            long incrementalEnd = System.nanoTime();
            incrementalNanos += incrementalEnd - incrementalStart;

            long fullStart = System.nanoTime();
            builder.buildWithOrigin(
                chronology, visuals(), THICKNESS, classesFor(chronology),
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            long fullEnd = System.nanoTime();
            fullNanos += fullEnd - fullStart;
        }
        return new long[] {incrementalNanos, fullNanos};
    }

    private static List<ChainTarget> line(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index, 0, 0));
        }
        return targets;
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

    private static void assertByteEqual(String label, ChainPreviewMesh expected, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(label + " vertices", expected.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(label + " colors", expected.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(label + " indices", expected.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(label + " aux", expected.getAux(), actual.getAux());
        Assert.assertEquals(label + " originX", expected.getOriginX(), actual.getOriginX());
        Assert.assertEquals(label + " blockCount", expected.getBlockCount(), actual.getBlockCount());
    }

    private static boolean containsOrder(ChainPreviewMesh mesh, int expectedOrder) {
        byte[] aux = mesh.getAux();
        for (int vertex = 0; vertex < aux.length / 4; vertex++) {
            int order = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            if (order == expectedOrder) {
                return true;
            }
        }
        return false;
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
        Assert.assertTrue("block@" + worldX + " 顶点缺失", found > 0);
    }
}
