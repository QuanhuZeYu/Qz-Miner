package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.PreviewLodMode;

/**
 * T22 极端规模 LOD / alpha 剔除独立契约探针（B2.4 / task-21）。
 *
 * <p>独立口径：探针自带 fade 参数（fadeStart=0 / fadeEnd=100 / maxAlpha=1 / minAlpha=0.05），
 * 目标放在 X 轴整数格、相机放在 (0.5 - d, 0.5, 0.5)，使「方块中心到相机距离」恰为整数 d，
 * 期望 alpha 由 temp/chain-preview/verify/cp_verify_lod_alpha_table.py 独立验算：
 * fresh 判定 alpha &lt;= enter 剔除；连带滞回记忆时 alpha &gt;= exit(=enter+0.05) 才恢复。</p>
 *
 * <p>不复用 owner 探针的取样点与断言；只覆盖任务要求：剔除边界（alpha 等于 epsilon）、
 * lod=off 与默认快照等价、索引/aux 一致性、双阈值防抖单调性、剔除计数可观察。</p>
 */
public class LodCullingContractTest {

    private static final float MAX_ALPHA = 1.0F;
    private static final float MIN_ALPHA = 0.05F;
    private static final double FADE_START = 0.0D;
    private static final double FADE_END = 100.0D;

    private static ChainPreviewMeshBuilder.VisualParameters visuals(
            int distance, boolean lodEnabled, float lodMinAlpha) {
        return new ChainPreviewMeshBuilder.VisualParameters(
            cameraFor(distance), 0.5D, 0.5D,
            FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F,
            lodEnabled, lodMinAlpha);
    }

    /** 相机放在 (0.5 - d, .5, .5)，使格 (0,0,0) 的中心距离恰为 d。 */
    private static double cameraFor(int distance) {
        return 0.5D - (double) distance;
    }

    private static List<ChainTarget> targetsAt(int... xs) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int x : xs) {
            targets.add(new ChainTarget(x, 0, 0));
        }
        return targets;
    }

    @Test
    public void lodOffNeverCullsAtAlphaFloor() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh mesh = builder.build(
            targetsAt(0, 300), visuals(0, false, MIN_ALPHA));
        Assert.assertEquals("lod=off 必须逐字不剔除", 0, mesh.getCulledTargetCount());
        Assert.assertEquals(2, mesh.getBlockCount());
        Assert.assertFalse(mesh.isTruncated());
        Assert.assertEquals("lod=off 不得污染滞回记忆", 0, builder.getLodHysteresisMemorySize());
        VerifyMeshAudit.Report report = VerifyMeshAudit.audit(mesh);
        Assert.assertEquals(0, report.indexOutOfRange);
        Assert.assertEquals(0, report.indexCountNotQuadAligned);
    }

    @Test
    public void enterBoundaryIsInclusiveOnBothSides() {
        // 每个子用例独立 builder：共享 builder 会把已剔除位置留在滞回记忆中（见下一用例）。
        // d=100：distance >= fadeEnd -> alpha == minAlpha == enter，必须剔除（边界取等号）。
        ChainPreviewMesh atEnter = new ChainPreviewMeshBuilder().build(targetsAt(0), visuals(100, true, MIN_ALPHA));
        Assert.assertEquals("alpha == epsilon 必须剔除", 1, atEnter.getCulledTargetCount());
        Assert.assertTrue("剔除目标不得生成几何", atEnter.isEmpty());

        // d=99：alpha≈0.0689 > enter，fresh 目标必须保留。
        ChainPreviewMesh aboveEnter = new ChainPreviewMeshBuilder().build(targetsAt(0), visuals(99, true, MIN_ALPHA));
        Assert.assertEquals("alpha > epsilon 不得剔除", 0, aboveEnter.getCulledTargetCount());
        Assert.assertEquals(1, aboveEnter.getBlockCount());

        // d=200：同样落在 minAlpha 地板上，确认不是 d 的偶然。
        ChainPreviewMesh far = new ChainPreviewMeshBuilder().build(targetsAt(0), visuals(200, true, MIN_ALPHA));
        Assert.assertEquals(1, far.getCulledTargetCount());
    }

    @Test
    public void deadBandOutcomeDependsOnHysteresisMemory() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> single = targetsAt(0);

        ChainPreviewMesh culled = builder.build(single, visuals(100, true, MIN_ALPHA));
        Assert.assertEquals(1, culled.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // alpha≈0.0876 处于 (enter, exit) 死区：已在记忆中 -> 继续剔除。
        ChainPreviewMesh remembered = builder.build(single, visuals(98, true, MIN_ALPHA));
        Assert.assertEquals("死区内已剔除目标必须保持剔除", 1, remembered.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // alpha≈0.2305 >= exit：必须恢复并释放记忆。
        ChainPreviewMesh released = builder.build(single, visuals(90, true, MIN_ALPHA));
        Assert.assertEquals("alpha >= exit 必须恢复", 0, released.getCulledTargetCount());
        Assert.assertEquals("恢复后必须释放滞回记忆", 0, builder.getLodHysteresisMemorySize());

        // 同一 alpha≈0.0876，但此时记忆为空（fresh）-> 必须保留：证明结论由双阈值记忆决定。
        ChainPreviewMesh fresh = builder.build(single, visuals(98, true, MIN_ALPHA));
        Assert.assertEquals("死区内 fresh 目标不得剔除", 0, fresh.getCulledTargetCount());
    }

    @Test
    public void approachSweepReleasesAtExitThresholdOnly() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> single = targetsAt(0);
        int previousKept = 0;
        StringBuilder trace = new StringBuilder();
        for (int distance = 100; distance >= 90; distance--) {
            ChainPreviewMesh mesh = builder.build(single, visuals(distance, true, MIN_ALPHA));
            int kept = mesh.getCulledTargetCount() == 0 ? 1 : 0;
            trace.append(distance).append(':').append(kept).append(' ');
            Assert.assertTrue("相机持续接近时保留状态不得回退: " + trace, kept >= previousKept);
            previousKept = kept;
        }
        // 期望（独立验算）：d=100/99/98 剔除，d<=97 恢复。
        Assert.assertEquals("释放点必须是 exit 首次满足处", 1, previousKept);
        Assert.assertEquals("恢复后记忆必须清空", 0, builder.getLodHysteresisMemorySize());
        Assert.assertTrue("trace=" + trace, trace.toString().startsWith("100:0 99:0 98:0 97:1 "));
    }

    @Test
    public void culledTargetsDoNotConsumeQuota() {
        List<ChainTarget> many = new ArrayList<ChainTarget>();
        for (int index = 0; index < 5000; index++) {
            many.add(new ChainTarget(index * 3, 0, 0));
        }
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        // enter=1.0：alpha 上界即 1.0，故全部剔除。
        ChainPreviewMesh allCulled = builder.build(many, visuals(0, true, 1.0F));
        Assert.assertEquals(5000, allCulled.getCulledTargetCount());
        Assert.assertFalse("剔除目标不得占用 4096 配额触发截断", allCulled.isTruncated());
        Assert.assertEquals(0, allCulled.getBlockCount());
        Assert.assertEquals(0, allCulled.getVertexFloatCount());
        Assert.assertTrue(
            "滞回记忆必须有界: " + builder.getLodHysteresisMemorySize(),
            builder.getLodHysteresisMemorySize() <= ChainPreviewMeshBuilder.MAX_RENDER_TARGETS);

        // 对照组：同样 5000 个目标在 lod=off 下必须触发 4096 配额截断，证明配额本身完好。
        ChainPreviewMesh noLod = new ChainPreviewMeshBuilder().build(many, visuals(0, false, MIN_ALPHA));
        Assert.assertEquals(0, noLod.getCulledTargetCount());
        Assert.assertTrue("配额旁路", noLod.isTruncated());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, noLod.getBlockCount());
    }

    @Test
    public void mixedBuildKeepsGeometrySelfConsistent() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh mesh = builder.build(targetsAt(0, 200), visuals(0, true, MIN_ALPHA));
        Assert.assertEquals("仅远处目标被剔除", 1, mesh.getCulledTargetCount());
        Assert.assertEquals(1, mesh.getBlockCount());
        Assert.assertEquals("几何只覆盖保留目标", 0, mesh.getOriginX());
        Assert.assertTrue(mesh.isAuxAvailable());
        int vertexCount = mesh.getVertexFloatCount() / 3;
        Assert.assertEquals("aux 必须逐顶点一槽", vertexCount * ChainPreviewMesh.AUX_BYTES_PER_VERTEX, mesh.getAuxByteCount());
        Assert.assertEquals(vertexCount * 4, mesh.getColorFloatCount());
        int[] indices = mesh.getIndices();
        for (int index : indices) {
            Assert.assertTrue("索引越界: " + index, index >= 0 && index < vertexCount);
        }
        float[] vertices = mesh.getVertices();
        for (int offset = 0; offset + 2 < vertexCount * 3; offset += 3) {
            Assert.assertTrue("被剔除目标不得留下顶点", vertices[offset] < 2.0F);
        }
        float[] colors = mesh.getColors();
        for (int offset = 3; offset < vertexCount * 4; offset += 4) {
            Assert.assertTrue("alpha 越界: " + colors[offset], colors[offset] >= MIN_ALPHA && colors[offset] <= MAX_ALPHA);
        }
        VerifyMeshAudit.Report report = VerifyMeshAudit.audit(mesh);
        Assert.assertEquals(0, report.indexOutOfRange);
        Assert.assertEquals(0, report.indexCountNotQuadAligned);
        Assert.assertEquals(0, report.unusedVertex);

        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, null, 0,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), 1L, 1L);
        Assert.assertEquals("剔除计数必须进入绘制计划", 1, plan.getCulledTargetCount());
        Assert.assertEquals(vertexCount, plan.getVertexCount());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Lod.MODE_OFF, plan.getLodId());
    }

    @Test
    public void cullCountersObservableAndOffNeverGrows() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.recordCulled(3);
        Assert.assertEquals(3L, counters.getCulledTargets());
        Assert.assertEquals(1L, counters.getCullEvents());
        counters.recordCulled(0);
        Assert.assertEquals("lod=off 传 0 不得增长", 3L, counters.getCulledTargets());
        Assert.assertEquals(1L, counters.getCullEvents());
        counters.recordCulled(2);
        Assert.assertEquals(5L, counters.getCulledTargets());
        Assert.assertEquals(2L, counters.getCullEvents());
        counters.reset();
        Assert.assertEquals(0L, counters.getCulledTargets());
        Assert.assertEquals(0L, counters.getCullEvents());
    }

    @Test
    public void lodOffGeometryEqualsDefaultSnapshot() {
        List<ChainTarget> targets = targetsAt(0, 1, 2, 99, 100, 200);
        ChainPreviewMesh defaultSnapshot = new ChainPreviewMeshBuilder().build(
            targets, visuals(0, false, MIN_ALPHA));
        ChainPreviewMesh explicitOff = new ChainPreviewMeshBuilder().build(
            targets, visuals(0, true, MIN_ALPHA).withLod(false, 0.9F));
        Assert.assertEquals(0, defaultSnapshot.getCulledTargetCount());
        Assert.assertEquals(0, explicitOff.getCulledTargetCount());
        Assert.assertArrayEquals(defaultSnapshot.vertexArray(), explicitOff.vertexArray(), 0.0F);
        Assert.assertArrayEquals(defaultSnapshot.colorArray(), explicitOff.colorArray(), 0.0F);
        Assert.assertArrayEquals(defaultSnapshot.indexArray(), explicitOff.indexArray());
        Assert.assertArrayEquals(defaultSnapshot.auxArray(), explicitOff.auxArray());
        Assert.assertEquals(defaultSnapshot.isTruncated(), explicitOff.isTruncated());

        String defaultId = PreviewLodMode.defaultValue().id();
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Lod.MODE_OFF, ChainPreviewDrawPlan.Visuals.Lod.fromConfig(defaultId, MIN_ALPHA).getModeId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Lod.MODE_OFF, ChainPreviewDrawPlan.Visuals.Lod.fromConfig(null, MIN_ALPHA).getModeId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Lod.MODE_OFF, ChainPreviewDrawPlan.Visuals.Lod.fromConfig("bogus", MIN_ALPHA).getModeId());
        Assert.assertEquals(
            ChainPreviewDrawPlan.Visuals.Lod.MODE_OFF,
            ChainPreviewDrawPlan.Visuals.BASELINE.getLod().getModeId());
        Assert.assertEquals(
            "默认快照必须保持 lod=off 等价",
            0.0F,
            ChainPreviewDrawPlan.Visuals.BASELINE.getLod().getMinAlpha(),
            0.0F);
        ChainPreviewDrawPlan.Visuals.Lod auto = ChainPreviewDrawPlan.Visuals.Lod.fromConfig(
            ChainPreviewDrawPlan.Visuals.Lod.MODE_AUTO, 0.05F);
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Lod.MODE_AUTO, auto.getModeId());
        Assert.assertEquals(0.05F, auto.getMinAlpha(), 1.0E-6F);
    }
}
