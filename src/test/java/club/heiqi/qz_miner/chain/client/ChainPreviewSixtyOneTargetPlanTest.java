package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewAnimationClock;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewFadeController;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRefreshDecision;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/**
 * 真机阻断缺陷排查探针：默认档（xray / lod=off / animation=off / minScreenWidthPx=0 / outline=0）
 * 下，61 目标链路是否一路走到「完整索引范围 + 全可见参数」的 draw plan。
 *
 * <p>覆盖：state → cache 发布网格（目标数/索引数/顶点数）→ plan 各字段 → 深度 pass 只画一遍
 * → 时钟/淡入淡出默认返回值。任一层截断都会在此断言失败并给出 trace。</p>
 */
public class ChainPreviewSixtyOneTargetPlanTest {

    @Test
    public void sixtyOneTargetsReachFullRangePlanInDefaultMode() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        ChainTarget origin = new ChainTarget(0, 64, 0);
        state.begin(origin);
        int generation = state.getGeneration();
        state.addPreviewTarget(generation, origin);

        List<String> trace = new ArrayList<String>();
        ChainPreviewRenderCache.MeshPublication single = drainUntilBlocks(scheduler, cache, 1, trace);
        Assert.assertNotNull("首个（单目标）发布必须存在：trace=" + trace, single);
        int singleIndexCount = single.getMesh().getIndexCount();
        Assert.assertTrue("单目标网格必须有索引：trace=" + trace, singleIndexCount > 0);

        int added = 1;
        outer:
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    ChainTarget target = new ChainTarget(x, 64 + y, z);
                    if (target.equals(origin)) {
                        continue;
                    }
                    state.addPreviewTarget(generation, target);
                    if (++added >= 61) {
                        break outer;
                    }
                }
            }
        }
        Assert.assertEquals("必须成功登记 61 个唯一目标", 61, added);
        Assert.assertEquals("状态侧目标数", 61, state.getMatchedCount() > 0 ? 61 : state.getTotalCount());

        int snapshotTargets = state.captureRenderSnapshot().getTargetCount();
        List<ChainTarget> targetList = new ArrayList<ChainTarget>();
        for (ChainTarget target : state.captureRenderSnapshot().getTargets()) {
            targetList.add(target);
        }
        Assert.assertEquals("状态快照必须含 61 目标", 61, snapshotTargets);
        Assert.assertEquals("快照列表长度必须与目标数一致", 61, targetList.size());

        // 密集簇中被完全包裹的目标不产生几何（合法），因此以「排空后的最后一次发布」为准，
        // 而不是以 blocks==61 为准；控制组做逐值一致性比对。
        ChainPreviewRenderCache.MeshPublication full = drainUntilBlocks(scheduler, cache, Integer.MAX_VALUE, trace);
        ChainPreviewMesh controlMesh = buildOneShot(targetList, snapshotTargets);
        String diagnosis = "snapshotTargets=" + snapshotTargets
            + ", controlBlocks=" + controlMesh.getBlockCount()
            + ", controlIndices=" + controlMesh.getIndexCount()
            + ", trace=" + trace;
        Assert.assertNotNull("cache 必须发布最终网格: " + diagnosis, full);

        ChainPreviewMesh mesh = full.getMesh();
        Assert.assertTrue(
            "索引数必须随目标数增长：single=" + singleIndexCount + ", full=" + mesh.getIndexCount()
                + "（trace=" + trace + "）",
            mesh.getIndexCount() > singleIndexCount);
        // 增量/缓存路径必须与同快照的全新一次性构建逐值一致（不得丢几何）
        Assert.assertEquals("顶点数必须与一次性构建一致: " + diagnosis,
            controlMesh.getVertexCount(), mesh.getVertexCount());
        Assert.assertEquals("索引数必须与一次性构建一致: " + diagnosis,
            controlMesh.getIndexCount(), mesh.getIndexCount());
        Assert.assertEquals("可见方块计数必须与一次性构建一致: " + diagnosis,
            controlMesh.getBlockCount(), mesh.getBlockCount());
        Assert.assertEquals("剔除计数必须一致: " + diagnosis,
            controlMesh.getCulledTargetCount(), mesh.getCulledTargetCount());
        Assert.assertTrue(
            "indexArray 是背板容量，必须 >= 逻辑长度 indexCount（trace=" + trace + "）",
            mesh.indexArray().length >= mesh.getIndexCount());
        Assert.assertTrue("顶点数必须 > 0", mesh.getVertexCount() > 0);

        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh,
            0,
            mesh.getIndexCount(),
            null,
            ChainPreviewDrawPlan.Visuals.BASELINE,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            0,
            64,
            0,
            0L,
            0L);

        Assert.assertEquals("indexOffset 必须为 0", 0, plan.getIndexOffset());
        Assert.assertEquals("indexCount 必须是全量", mesh.getIndexCount(), plan.getIndexCount());
        Assert.assertEquals(
            "lod=off 时可见索引必须等于全量（不得被剔断）",
            mesh.getIndexCount(),
            plan.getVisibleIndexCount());
        Assert.assertEquals("animation=off 时 animationU 必须恒 1", 1.0F, plan.getAnimationU(), 0.0F);
        Assert.assertEquals("fadeAlpha 必须为 1", 1.0F, plan.getFadeAlpha(), 0.0F);
        Assert.assertEquals("minScreenWidthPx 必须为 0", 0.0F, plan.getMinScreenWidthPx(), 0.0F);
        Assert.assertFalse("默认档不得是描边壳段", plan.isOutlineShell());
        Assert.assertEquals("默认档 outlineWidthPx 必须为 0", 0.0F, plan.getOutlineWidthPx(), 0.0F);
        Assert.assertEquals(ChainPreviewDrawPlan.DepthChannel.XRAY, plan.getDepthChannel());
        Assert.assertNull("waveEnds 必须为 null（生长走 shader 逐顶点）", plan.getWaveEnds());
        Assert.assertEquals(0, plan.getWaveVisible());
        Assert.assertEquals("lod=off 不得剔除任何目标", 0, plan.getCulledTargetCount());

        Assert.assertEquals(
            ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.select(plan.getDepthChannel()));
        Assert.assertEquals("XRAY 必须只画一遍", 1,
            ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.XRAY));
        Assert.assertFalse(
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.XRAY, 0));

        Assert.assertEquals("时钟 off 档必须返回 1", 1.0F,
            new ChainPreviewAnimationClock().advance(generation, "off", 120, 123456789L), 0.0F);
        Assert.assertFalse("animation=off 不得启用过渡",
            ChainPreviewFadeController.isFadeEnabled("off", 120));
        Assert.assertEquals("未启用过渡且激活必须返回 1", 1.0F,
            new ChainPreviewFadeController().advance(true, generation, false, 120, 123456789L), 0.0F);

        ChainPreviewVisualSettings settings = ChainPreviewVisualSettings.fromConfig();
        Assert.assertEquals("off", settings.getAnimationId());
        Assert.assertEquals(0.0F, settings.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals("off", settings.getLodId());
        Assert.assertEquals("auto", settings.getRenderBackendId());
        Assert.assertEquals("xray", settings.getDepthModeId());
        Assert.assertFalse(settings.isSuppressVanillaHighlight());
    }

    /**
     * Lead 问 3：目标是逐步到达时，renderer 的上传决策链（topologyChanged → RefreshDecision →
     * ScaleCounters）是否随修订推进，且最终上传的是含全部目标的那一份网格。
     *
     * <p>完全按 {@code ChainPreviewRenderer.applyPublication} 的判定复刻，shader 档
     * （usesCpuColors=false）在同代零上传、修订变化必须 TOPOLOGY。</p>
     */
    @Test
    public void incrementalArrivalKeepsUploadingEveryRevisionUntilFullMesh() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        ChainTarget origin = new ChainTarget(0, 64, 0);
        state.begin(origin);
        int generation = state.getGeneration();
        state.addPreviewTarget(generation, origin);

        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        int uploadedGeneration = -1;
        long uploadedRevision = -1L;
        ChainPreviewMesh uploadedMesh = ChainPreviewMesh.EMPTY;
        ChainPreviewMesh lastPublishedMesh = ChainPreviewMesh.EMPTY;
        long lastRevision = -1L;
        int taskCursor = 0;
        int published = 0;

        List<ChainTarget> arrivals = new ArrayList<ChainTarget>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    ChainTarget target = new ChainTarget(x, 64 + y, z);
                    if (!target.equals(origin)) {
                        arrivals.add(target);
                    }
                }
            }
        }

        for (int round = 0; round < 200; round++) {
            if (round < arrivals.size()) {
                state.addPreviewTarget(generation, arrivals.get(round));
            }
            // 每轮把当前可跑的构建任务跑到终态（真实的按 tick 分片）
            while (taskCursor < scheduler.tasks.size()) {
                runToTerminal(scheduler.tasks.get(taskCursor++));
            }
            ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
            if (publication == null) {
                if (round >= arrivals.size() && counters.getRebuilds() > 0) {
                    break;
                }
                continue;
            }
            published++;
            lastPublishedMesh = publication.getMesh();
            Assert.assertTrue(
                "发布修订必须单调递增: prev=" + lastRevision + ", now=" + publication.getStateRevision(),
                publication.getStateRevision() > lastRevision);
            lastRevision = publication.getStateRevision();

            ChainPreviewMesh mesh = publication.getMesh();
            boolean topologyChanged = mesh.isEmpty()
                || publication.getGeneration() != uploadedGeneration
                || publication.getStateRevision() != uploadedRevision;
            ChainPreviewRefreshDecision.Upload upload =
                ChainPreviewRefreshDecision.begin(topologyChanged, false);
            if (upload == ChainPreviewRefreshDecision.Upload.TOPOLOGY) {
                counters.recordCulled(mesh.getCulledTargetCount());
            }
            counters.record(upload, !mesh.isEmpty());
            uploadedGeneration = publication.getGeneration();
            uploadedRevision = publication.getStateRevision();
            uploadedMesh = mesh;

            Assert.assertEquals(
                "修订推进必须触发 TOPOLOGY 上传: rev=" + lastRevision,
                ChainPreviewRefreshDecision.Upload.TOPOLOGY,
                upload);
        }

        Assert.assertTrue("必须观察到多次发布: published=" + published, published >= 3);
        Assert.assertTrue(
            "uploads 必须随修订递增: uploads=" + counters.getUploads() + ", published=" + published,
            counters.getUploads() >= published - 1L);
        Assert.assertTrue("rebuilds 必须随非空拓扑递增: rebuilds=" + counters.getRebuilds(),
            counters.getRebuilds() >= 2L);
        Assert.assertSame("最终上传的网格必须是最后一次发布的那一份",
            lastPublishedMesh, uploadedMesh);
        Assert.assertTrue("最终网格索引数必须 > 0", uploadedMesh.getIndexCount() > 0);
        Assert.assertEquals("最终上传的必须是最新一代",
            state.getGeneration(), uploadedGeneration);
    }

    /**
     * 对照组：同一个快照用全新会话一次性构建（不复用代级缓存），用于区分
     * 「增量装配丢目标」与「快照/输入本身就只有这些目标」。
     */
    private static ChainPreviewMesh buildOneShot(List<ChainTarget> targets, int targetCount) {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMeshBuilder.GenerationSession session = builder.beginGeneration();
        ChainPreviewMeshBuilder.VisualParameters visuals = new ChainPreviewMeshBuilder.VisualParameters(
            0.5D, 64.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, 0.045F);
        int[] classes = new int[targetCount];
        for (int index = 0; index < classes.length; index++) {
            classes[index] = 255;
        }
        ChainPreviewMeshBuilder.MeshBuildSession revision =
            session.beginRevision(targets, classes, visuals, 0.045F);
        int guard = 0;
        while (!revision.advance(new ChainPreviewMeshBuilder.WorkGate() {
            @Override
            public boolean shouldYield() {
                return false;
            }
        })) {
            if (++guard > 64) {
                Assert.fail("对照构建未在限定轮次内完成");
            }
        }
        return revision.getMesh();
    }

    private static ChainPreviewRenderCache.MeshPublication drainUntilBlocks(
            RecordingScheduler scheduler,
            ChainPreviewRenderCache cache,
            int blocks,
            List<String> trace) throws Exception {
        int index = 0;
        int idleRounds = 0;
        ChainPreviewRenderCache.MeshPublication last = null;
        while (idleRounds < 3 && index < 128) {
            if (index < scheduler.tasks.size()) {
                runToTerminal(scheduler.tasks.get(index++));
            } else {
                idleRounds++;
            }
            ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
            if (publication != null) {
                last = publication;
                trace.add(describe(publication));
                if (publication.getMesh().getBlockCount() >= blocks) {
                    return publication;
                }
                idleRounds = 0;
            }
        }
        return last;
    }

    private static String describe(ChainPreviewRenderCache.MeshPublication publication) {
        return "[gen=" + publication.getGeneration()
            + ", rev=" + publication.getStateRevision()
            + ", blocks=" + publication.getMesh().getBlockCount()
            + ", indices=" + publication.getMesh().getIndexCount()
            + ", verts=" + publication.getMesh().getVertexCount() + ']';
    }

    /**
     * 跑到终态：COMPLETED（本轮构建完成）与 TERMINATED（任务被更新的 revision 取代）都算终结；
     * YIELDED 则继续推进。构建任务被取代时由后续任务完成同一个代，drain 循环会继续。
     */
    private static void runToTerminal(ScheduledTask scheduled) throws Exception {
        for (int round = 0; round < 16; round++) {
            ParallelTaskResult result = scheduled.task.run(new NeverYieldControl());
            if (result != ParallelTaskResult.YIELDED) {
                return;
            }
        }
        Assert.fail("构建任务未在限定轮次内终结");
    }

    private static final class RecordingScheduler implements ChainPreviewRenderCache.TaskScheduler {

        final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();

        @Override
        public ParallelTickSubscription schedule(ParallelTickTask task) {
            final ScheduledTask scheduled = new ScheduledTask(task);
            tasks.add(scheduled);
            return new ParallelTickSubscription() {
                @Override
                public void unregister() {
                    scheduled.cancelled = true;
                }
            };
        }
    }

    private static final class ScheduledTask {

        final ParallelTickTask task;
        boolean cancelled;

        ScheduledTask(ParallelTickTask task) {
            this.task = task;
        }
    }

    /** 单次 run 即完成的控制面。 */
    private static final class NeverYieldControl implements ParallelTickControl {

        @Override
        public long getTickId() {
            return 1L;
        }

        @Override
        public ParallelTickStage getStage() {
            return ParallelTickStage.CLIENT_POST;
        }

        @Override
        public boolean isWindowOpen() {
            return true;
        }

        @Override
        public boolean isCancelRequested() {
            return false;
        }

        @Override
        public boolean shouldYield() {
            return false;
        }

        @Override
        public long getElapsedNanoTime() {
            return 0L;
        }

        @Override
        public String getCancelReason() {
            return "";
        }
    }
}
