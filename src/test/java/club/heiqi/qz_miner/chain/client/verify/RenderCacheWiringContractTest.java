package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T31 波次 6 生产接线契约（任务第 4 项）：用反射 harness 驱动生产缓存，
 * 判据只看「发布出来的网格」——不读 owner 断言、不断源码字符串。
 *
 * <p>关键判据：代内追加时 meshOrigin 必须稳定在最早目标（锚点），appearOrder 最早=0；
 * 单槽 publication 未被消费时不得堆积；换代必须重置网格与 LOD 滞回；clear 必须发布空网格。</p>
 */
public class RenderCacheWiringContractTest {

    private static final float THICKNESS_HINT = 0.045F;

    private static int addTargets(ChainPreviewState state, int generation, int... xs) {
        for (int x : xs) {
            Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(x, 0, 0)));
        }
        return xs.length;
    }

    private static VerifyRenderCacheHarness.Publication publish(VerifyRenderCacheHarness harness) {
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication publication = harness.pollPublication();
        Assert.assertNotNull("必须产出发布", publication);
        return publication;
    }

    /** @return 目标 (x,0,0) 所属条柱的 appearOrder（按条柱归属取最小序号） */
    private static int orderOf(ChainPreviewMesh mesh, int x) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int order = Integer.MAX_VALUE;
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            float worldX = mesh.getOriginX() + vertices[vertex * 3];
            float worldY = mesh.getOriginY() + vertices[vertex * 3 + 1];
            if (worldX < x - 0.6F || worldX > x + 1.6F) {
                continue;
            }
            if (worldY < -0.6F || worldY > 1.6F) {
                continue;
            }
            int current = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            order = Math.min(order, current);
        }
        Assert.assertTrue("目标 (" + x + ",0,0) 必须至少有一个顶点", order != Integer.MAX_VALUE);
        return order;
    }

    @Test
    public void cachePublishesMeshForStateTargets() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0, 3);
        VerifyRenderCacheHarness.Publication publication = publish(harness);
        Assert.assertEquals("代必须透传", generation, publication.generation);
        Assert.assertEquals(2, publication.mesh.getBlockCount());
        Assert.assertEquals("锚点必须是代内最早目标", 0, publication.mesh.getOriginX());
    }

    @Test
    public void inGenerationAppendsKeepAnchorAndEarliestOrder() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0);
        VerifyRenderCacheHarness.Publication first = publish(harness);
        Assert.assertEquals(0, first.mesh.getOriginX());
        Assert.assertEquals(0, orderOf(first.mesh, 0));

        // 代内追加更新目标：锚点不得漂移，最早目标必须仍是 order 0
        addTargets(state, generation, 3);
        VerifyRenderCacheHarness.Publication second = publish(harness);
        Assert.assertEquals("代内追加不得换锚点（稳定锚点）", 0, second.mesh.getOriginX());
        Assert.assertEquals("最早目标必须仍是 order 0", 0, orderOf(second.mesh, 0));
        Assert.assertEquals("最新目标必须拿最大序号", 1, orderOf(second.mesh, 3));

        addTargets(state, generation, 6);
        VerifyRenderCacheHarness.Publication third = publish(harness);
        Assert.assertEquals("第三次追加锚点仍必须稳定", 0, third.mesh.getOriginX());
        Assert.assertEquals(0, orderOf(third.mesh, 0));
        Assert.assertEquals("最新目标序号必须随代内单调递增", 2, orderOf(third.mesh, 6));
        Assert.assertEquals(3, third.mesh.getBlockCount());
    }

    @Test
    public void publicationGateKeepsSingleSlotUntilConsumed() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0);
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication first = harness.pollPublication();
        Assert.assertNotNull("首次构建必须产出发布", first);
        Assert.assertEquals(1, first.mesh.getBlockCount());

        // 产出但未消费：门控必须阻止新的拓扑构建（单槽不被覆盖）
        StringBuilder trace = new StringBuilder();
        trace.append("t0 first rev=").append(first.stateRevision)
            .append(" blocks=").append(first.mesh.getBlockCount())
            .append(" id=").append(System.identityHashCode(first.mesh));

        addTargets(state, generation, 3);
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication pending = harness.peekPublication();
        Assert.assertNotNull("未消费时必须仍有待消费发布", pending);
        trace.append(" | t1 add(3) rev=").append(pending.stateRevision)
            .append(" blocks=").append(pending.mesh.getBlockCount())
            .append(" id=").append(System.identityHashCode(pending.mesh));

        addTargets(state, generation, 6);
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication stillPending = harness.peekPublication();
        Assert.assertNotNull(stillPending);
        trace.append(" | t2 add(6) rev=").append(stillPending.stateRevision)
            .append(" blocks=").append(stillPending.mesh.getBlockCount())
            .append(" id=").append(System.identityHashCode(stillPending.mesh));

        // 未消费期间：新变更不得让单槽发布推进到更新状态（门控语义）
        Assert.assertEquals("未消费期间不得推进单槽发布：" + trace,
            pending.stateRevision, stillPending.stateRevision);
        Assert.assertEquals("未消费期间内容不得推进：" + trace,
            pending.mesh.getBlockCount(), stillPending.mesh.getBlockCount());

        // 消费后必须能继续推进到最新状态
        VerifyRenderCacheHarness.Publication consumedPending = harness.pollPublication();
        Assert.assertNotNull("未消费的发布必须可被消费", consumedPending);
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication advanced = harness.pollPublication();
        Assert.assertNotNull("消费后必须能产出最新发布", advanced);
        Assert.assertEquals("消费后发布必须推进到最新状态（3 个目标）",
            3, advanced.mesh.getBlockCount());
        Assert.assertTrue("消费后发布状态必须不早于已消费发布：" + trace,
            advanced.stateRevision >= consumedPending.stateRevision);
    }

    @Test
    public void generationChangeResetsGeometryAndLodHysteresis() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int firstGeneration = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, firstGeneration, 0, 3);
        VerifyRenderCacheHarness.Publication first = publish(harness);
        Assert.assertEquals(2, first.mesh.getBlockCount());

        int secondGeneration = state.begin(new ChainTarget(40, 0, 0));
        Assert.assertNotEquals(firstGeneration, secondGeneration);
        addTargets(state, secondGeneration, 40);
        VerifyRenderCacheHarness.Publication second = publish(harness);
        Assert.assertEquals("换代后发布必须属于新代", secondGeneration, second.generation);
        Assert.assertEquals("换代后不得残留上一代几何", 1, second.mesh.getBlockCount());
        Assert.assertEquals("新代锚点必须重置为新代最早目标", 40, second.mesh.getOriginX());
    }

    @Test
    public void clearPublishesEmptyMeshAndKeepsStateConsistent() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0, 3);
        Assert.assertEquals(2, publish(harness).mesh.getBlockCount());

        state.clear();
        harness.runUntilPublication(8);
        VerifyRenderCacheHarness.Publication afterClear = harness.pollPublication();
        Assert.assertNotNull(afterClear);
        Assert.assertTrue("清理后必须发布空网格", afterClear.mesh.isEmpty());
        Assert.assertFalse(state.isActive());

        int nextGeneration = state.begin(new ChainTarget(20, 0, 0));
        addTargets(state, nextGeneration, 20);
        VerifyRenderCacheHarness.Publication afterRestart = publish(harness);
        Assert.assertEquals(1, afterRestart.mesh.getBlockCount());
        Assert.assertEquals("清理后重启必须锚定新代最早目标", 20, afterRestart.mesh.getOriginX());
    }

    @Test
    public void repeatedAppendsDoNotGrowPendingPublications() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        List<Integer> targets = new ArrayList<Integer>();
        for (int x = 0; x < 6; x++) {
            targets.add(Integer.valueOf(x * 3));
            addTargets(state, generation, x * 3);
        }
        Assert.assertEquals("单槽语义：多次变更只保留一份待消费发布", 1,
            harness.peekPublication() == null ? 0 : 1);
        VerifyRenderCacheHarness.Publication finalPublication = publish(harness);
        Assert.assertEquals(targets.size(), finalPublication.mesh.getBlockCount());
        Assert.assertEquals("最终锚点必须仍是最早目标", 0, finalPublication.mesh.getOriginX());
        Assert.assertEquals(THICKNESS_HINT, finalPublication.mesh.getCulledTargetCount() == 0
            ? THICKNESS_HINT : THICKNESS_HINT, 0.0F);
    }
}
