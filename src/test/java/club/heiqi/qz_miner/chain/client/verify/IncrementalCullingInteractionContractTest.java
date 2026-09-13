package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T31 波次 6 增量会话 × LOD 剔除/滞回重置交互（任务第 4 项部分）。
 *
 * <p>滞回记忆是 Builder 代际共享状态，而 GenerationSession 是长寿命会话：主线程置位的
 * 「滞回清理请求」必须被下一次 extend 消费且只消费一次。判据用死区对照——
 * 同一 alpha（d=98，α≈0.0876 ∈ (enter, exit)）下：记忆中有该位置→剔除，记忆被清理→保留。</p>
 */
public class IncrementalCullingInteractionContractTest {

    private static final float MIN_ALPHA = 0.05F;
    private static final double FADE_START = 0.0D;
    private static final double FADE_END = 100.0D;

    /** 相机放在 (0.5 - d, .5, .5)，使格 (0,0,0) 的中心到相机距离恰为 d。 */
    private static VisualParameters visuals(int distance, boolean lodEnabled) {
        return new VisualParameters(
            0.5D - (double) distance, 0.5D, 0.5D,
            FADE_START, FADE_END, 1.0F, MIN_ALPHA, 0.045F, lodEnabled, MIN_ALPHA);
    }

    private static ChainPreviewMesh extend(GenerationSession session, int distance) {
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>();
        snapshot.add(new ChainTarget(0, 0, 0));
        return session.extend(snapshot, null, visuals(distance, true), 0.045F);
    }

    @Test
    public void hysteresisResetIsConsumedByNextExtendAndChangesDeadBandOutcome() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();

        ChainPreviewMesh atEnter = extend(session, 100);
        Assert.assertEquals("alpha == enter 必须剔除", 1, atEnter.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // 主线程置位清理请求 -> 下一次 extend 消费
        builder.resetLodHysteresis();
        ChainPreviewMesh afterReset = extend(session, 98);
        Assert.assertEquals("滞回记忆被清理后死区目标必须恢复", 0, afterReset.getCulledTargetCount());
        Assert.assertEquals("清理必须完成", 0, builder.getLodHysteresisMemorySize());
        Assert.assertEquals("代内目标数不受剔除影响", 1, session.getGenerationTargetCount());

        // 再回到 enter 处重新形成记忆，且此前的清理请求已被消费（不再生效）
        ChainPreviewMesh again = extend(session, 100);
        Assert.assertEquals(1, again.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());
        ChainPreviewMesh deadBand = extend(session, 98);
        Assert.assertEquals("清理请求只消费一次：死区重新变回剔除",
            1, deadBand.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // 恢复侧：alpha >= exit 必须释放记忆
        ChainPreviewMesh released = extend(session, 90);
        Assert.assertEquals(0, released.getCulledTargetCount());
        Assert.assertEquals(0, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void lodOffIncrementalSessionNeverCullsAndIgnoresResetRequest() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        List<ChainTarget> snapshot = Arrays.asList(new ChainTarget(0, 0, 0));
        ChainPreviewMesh mesh = session.extend(
            snapshot, null, visuals(200, false), 0.045F);
        Assert.assertEquals("lod=off 不得剔除", 0, mesh.getCulledTargetCount());
        Assert.assertEquals(0, builder.getLodHysteresisMemorySize());
        builder.resetLodHysteresis();
        Assert.assertEquals("lod=off 下清理请求不得影响几何", 1, mesh.getBlockCount());
    }

    @Test
    public void fullyCulledGenerationStillReportsCountersAndEmptyGeometry() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        // enter=1.0：alpha 上界即 1.0，全部剔除
        VisualParameters allCulled = new VisualParameters(
            0.5D, 0.5D, 0.5D, 0.0D, 100.0D, 1.0F, 0.05F, 0.045F, true, 1.0F);
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>();
        for (int index = 0; index < 5; index++) {
            snapshot.add(new ChainTarget(index * 3, 0, 0));
        }
        ChainPreviewMesh mesh = session.extend(snapshot, null, allCulled, 0.045F);
        Assert.assertEquals("全剔除必须计入剔除目标数", 5, mesh.getCulledTargetCount());
        Assert.assertEquals(0, mesh.getBlockCount());
        Assert.assertTrue(mesh.isEmpty());
        Assert.assertEquals("代内累积目标数必须是 5（剔除不改变累积）",
            5, session.getGenerationTargetCount());
        Assert.assertFalse(mesh.isTruncated());
    }
}
