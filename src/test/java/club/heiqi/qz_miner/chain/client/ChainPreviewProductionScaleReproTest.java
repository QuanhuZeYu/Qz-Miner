package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.MeshBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 真机阻断缺陷现场复现（几何侧）：61 个**连续相邻**目标（矿脉形态）+ **生产形态**
 * （逐次 beginRevision 增量追加 + 分片 advance）必须产出完整几何，并与同快照一次性全量装配逐字节一致。
 *
 * <p>本类同时打印几何量（blockCount / 顶点 / 索引 / 唯一位置），供定位「只渲染一根条柱」的真因。</p>
 */
public class ChainPreviewProductionScaleReproTest {

    private static final float THICKNESS = 0.045F;

    /** 61 格相邻矿脉团（8×4×2，含跨层连接）。 */
    private static List<ChainTarget> vein(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index % 8, (index / 8) % 4, index / 32));
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

    /** 与生产同源的视觉参数：LOD=off、相机在玩家处（距离曲线不影响几何）。 */
    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 64.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, THICKNESS);
    }

    /** 分片推进（模拟 ParallelTick 让出），返回完成后的网格。 */
    private static ChainPreviewMesh advanceToCompletion(MeshBuildSession session) {
        int guards = 0;
        while (!session.advance(new ChainPreviewMeshBuilder.WorkGate() {
            private int checks;

            @Override
            public boolean shouldYield() {
                return ++checks > 2;
            }
        })) {
            if (++guards > 1_000_000) {
                Assert.fail("分片推进未收敛");
            }
        }
        return session.getMesh();
    }

    private static void assertByteEqual(String label, ChainPreviewMesh reference, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(label + " vertices", reference.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(label + " colors", reference.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(label + " indices", reference.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(label + " aux", reference.getAux(), actual.getAux());
        Assert.assertEquals(label + " originX", reference.getOriginX(), actual.getOriginX());
        Assert.assertEquals(label + " originY", reference.getOriginY(), actual.getOriginY());
        Assert.assertEquals(label + " originZ", reference.getOriginZ(), actual.getOriginZ());
        Assert.assertEquals(label + " blockCount", reference.getBlockCount(), actual.getBlockCount());
        Assert.assertEquals(label + " truncated", reference.isTruncated(), actual.isTruncated());
    }

    /** 生产首次构建形态：一次性 beginRevision（61 目标）+ 分片 advance。 */
    @Test
    public void oneShotSixtyOneAdjacentTargetsProduceFullGeometry() {
        List<ChainTarget> chronology = vein(61);
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronology);
        Collections.reverse(snapshot);

        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        MeshBuildSession revision =
            session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS);
        ChainPreviewMesh mesh = advanceToCompletion(revision);

        ChainPreviewMesh reference = new ChainPreviewMeshBuilder().buildWithOrigin(
            chronology, visuals(), THICKNESS, classesFor(chronology),
            session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());

        System.out.println("[t-prod-geom] case=oneshot targets=" + chronology.size()
            + " unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount()
            + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount()
            + " auxBytes=" + mesh.getAuxByteCount()
            + " origin=" + mesh.getOriginX() + "," + mesh.getOriginY() + "," + mesh.getOriginZ());
        Assert.assertEquals(61, session.getGenerationTargetCount());
        Assert.assertFalse(mesh.isEmpty());
        // 先证几何等价，再看待见块数（blockCount 口径 = 有可见段的块，稠密团内部块为 0 段）。
        assertByteEqual("oneshot61", reference, mesh);
        Assert.assertTrue("可见块数必须远大于 1: " + mesh.getBlockCount(),
            mesh.getBlockCount() > 1 && mesh.getBlockCount() <= 61);
    }

    /** 生产增量形态：每个修订新增 1 个目标（共 61 个修订），每步都必须与全量一致。 */
    @Test
    public void incrementalSixtyOneRevisionsMatchFullBuildAtEveryStep() {
        List<ChainTarget> chronology = vein(61);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainPreviewMesh mesh = null;
        for (int count = 1; count <= chronology.size(); count++) {
            List<ChainTarget> accumulated = new ArrayList<ChainTarget>(chronology.subList(0, count));
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            MeshBuildSession revision =
                session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS);
            mesh = advanceToCompletion(revision);

            ChainPreviewMesh reference = new ChainPreviewMeshBuilder().buildWithOrigin(
                accumulated, visuals(), THICKNESS, classesFor(accumulated),
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            Assert.assertEquals("rev" + count + " 唯一位置数", count, session.getGenerationTargetCount());
            assertByteEqual("rev" + count, reference, mesh);
            Assert.assertTrue("rev" + count + " 可见块数必须有界: " + mesh.getBlockCount(),
                mesh.getBlockCount() > 0 && mesh.getBlockCount() <= count);
        }
        Assert.assertNotNull(mesh);
        System.out.println("[t-prod-geom] case=incremental revisions=61 targets=61"
            + " unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount()
            + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount()
            + " auxBytes=" + mesh.getAuxByteCount());
        Assert.assertTrue("矿脉团可见块数必须远大于 1: " + mesh.getBlockCount(),
            mesh.getBlockCount() > 1 && mesh.getBlockCount() <= 61);
    }

    /** 61 格相邻直线（沿 X 连续）：链式矿脉/通道形态，可见块数必须等于目标数。 */
    @Test
    public void sixtyOneAdjacentLineProducesSixtyOneVisibleBars() {
        List<ChainTarget> line = new ArrayList<ChainTarget>();
        for (int index = 0; index < 61; index++) {
            line.add(new ChainTarget(index, 0, 0));
        }
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(line);
        Collections.reverse(snapshot);

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh mesh = advanceToCompletion(
            session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS));
        ChainPreviewMesh reference = new ChainPreviewMeshBuilder().buildWithOrigin(
            line, visuals(), THICKNESS, classesFor(line),
            session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());

        System.out.println("[t-prod-geom] case=line61 unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount() + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount() + " auxBytes=" + mesh.getAuxByteCount());
        Assert.assertEquals(61, session.getGenerationTargetCount());
        Assert.assertEquals("61 格相邻直线必须产出 61 个可见块", 61, mesh.getBlockCount());
        assertByteEqual("line61", reference, mesh);
    }

    /**
     * 生产时序混合：每个新目标一到就构建一次，另在两次新增之间插入「同快照、相机变化」的
     * 刷新修订（1 Hz 相机刷新形态）——所有修订都必须与同参数全量逐字节一致，且不得丢目标。
     */
    @Test
    public void cameraRefreshInterleavedAppendsKeepEveryTarget() {
        List<ChainTarget> line = new ArrayList<ChainTarget>();
        for (int index = 0; index < 61; index++) {
            line.add(new ChainTarget(index, 0, 0));
        }
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        int refreshes = 0;
        ChainPreviewMesh mesh = null;
        for (int count = 1; count <= line.size(); count++) {
            List<ChainTarget> accumulated = new ArrayList<ChainTarget>(line.subList(0, count));
            // 新增修订
            List<ChainTarget> snapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(snapshot);
            mesh = advanceToCompletion(
                session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS));

            // 同快照相机刷新修订（rev 不变、仅相机变化）
            VisualParameters moved = new VisualParameters(
                accumulated.get(count - 1).getX() + 0.5D, 64.5D, 0.5D, 2.0D, 6.0D,
                0.78F, 0.15F, THICKNESS);
            List<ChainTarget> refreshSnapshot = new ArrayList<ChainTarget>(accumulated);
            Collections.reverse(refreshSnapshot);
            mesh = advanceToCompletion(
                session.beginRevision(refreshSnapshot, classesFor(refreshSnapshot), moved, THICKNESS));
            refreshes++;

            ChainPreviewMesh reference = new ChainPreviewMeshBuilder().buildWithOrigin(
                accumulated, moved, THICKNESS, classesFor(accumulated),
                session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            Assert.assertEquals("rev" + count + " 唯一位置数", count, session.getGenerationTargetCount());
            Assert.assertEquals("rev" + count + " 可见块数（直线）", count, mesh.getBlockCount());
            assertByteEqual("cameraRefresh rev" + count, reference, mesh);
        }
        System.out.println("[t-prod-geom] case=appendsWithCameraRefresh revisions=" + line.size()
            + " refreshes=" + refreshes
            + " unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount()
            + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount());
        Assert.assertEquals(61, mesh.getBlockCount());
    }

    /** 反方向：同一坐标重复 61 次 ⇒ 唯一位置数 1、可见块数 1（几何按坐标去重是既定语义）。 */
    @Test
    public void repeatedSameCoordinateCollapsesToOneBar() {
        List<ChainTarget> repeated = new ArrayList<ChainTarget>();
        for (int index = 0; index < 61; index++) {
            repeated.add(new ChainTarget(7, 64, -3));
        }
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(repeated);
        Collections.reverse(snapshot);

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh mesh = advanceToCompletion(
            session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS));

        System.out.println("[t-prod-geom] case=repeatedSameCoordinate feeds=" + repeated.size()
            + " unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount() + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount());
        Assert.assertEquals("同一坐标重复 61 次只算 1 个唯一位置",
            1, session.getGenerationTargetCount());
        Assert.assertEquals("几何必须只有 1 根", 1, mesh.getBlockCount());
    }

    /** 上游重复目标（同一位置多次出现）不得把相邻矿脉折叠成一根条柱。 */
    @Test
    public void upstreamDuplicatesDoNotCollapseAdjacentVein() {
        List<ChainTarget> vein = vein(61);
        List<ChainTarget> withDuplicates = new ArrayList<ChainTarget>(vein);
        withDuplicates.add(vein.get(0));
        withDuplicates.add(vein.get(30));
        withDuplicates.add(vein.get(60));
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(withDuplicates);
        Collections.reverse(snapshot);

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh mesh = advanceToCompletion(
            session.beginRevision(snapshot, classesFor(snapshot), visuals(), THICKNESS));

        System.out.println("[t-prod-geom] case=duplicates unique=" + session.getGenerationTargetCount()
            + " blocks=" + mesh.getBlockCount() + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount());
        Assert.assertEquals("重复值不得占用唯一位置", 61, session.getGenerationTargetCount());
        Assert.assertTrue("重复值不得把矿脉折叠成一根条柱: " + mesh.getBlockCount(),
            mesh.getBlockCount() > 1 && mesh.getBlockCount() <= 61);
        Assert.assertEquals("重复目标场景必须与去重后全量一致",
            new ChainPreviewMeshBuilder().buildWithOrigin(
                vein(61), visuals(), THICKNESS, classesFor(vein(61)), 0, 0, 0).getIndexCount(),
            mesh.getIndexCount());
    }
}
