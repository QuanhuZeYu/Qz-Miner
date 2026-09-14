package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.BuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B2.4（task-21）：构建期 LOD 剔除（alpha &lt;= lodMinAlpha）与进出双阈值防抖。
 *
 * <p>口径：lod=off 逐字等于现状（忽略阈值、无记忆、无剔除）；lod=auto 仅剔除远处散点目标
 * （整条柱不生成几何，不占配额），enter = lodMinAlpha、exit = lodMinAlpha + 0.05；
 * 剔除后索引/顶点/aux 三者一致且 appearOrder 重新连续编号。超距合并/外壳档位不在本轮范围。</p>
 */
public class ChainPreviewLodCullingTest {

    @Test
    public void lodOffIgnoresThresholdsAndStaysByteIdentical() {
        List<ChainTarget> targets = scattered(5);
        ChainPreviewMesh baseline = new ChainPreviewMeshBuilder().build(
            targets, farVisuals(false, 0.0F));
        // 关闭 LOD 时阈值即使为 1.0（会剔除一切）也不得生效。
        ChainPreviewMesh ignored = new ChainPreviewMeshBuilder().build(
            targets, farVisuals(false, 1.0F));

        Assert.assertArrayEquals(baseline.getVertices(), ignored.getVertices(), 0.0F);
        Assert.assertArrayEquals(baseline.getColors(), ignored.getColors(), 0.0F);
        Assert.assertArrayEquals(baseline.getIndices(), ignored.getIndices());
        Assert.assertArrayEquals(baseline.getAux(), ignored.getAux());
        Assert.assertEquals(baseline.getBlockCount(), ignored.getBlockCount());
        Assert.assertEquals(0, ignored.getCulledTargetCount());
        Assert.assertEquals(5, ignored.getBlockCount());
    }

    @Test
    public void alphaEqualityBoundaryCullsAndSlightlyBelowRetains() {
        List<ChainTarget> targets = scattered(4);

        BuildSession culled = new ChainPreviewMeshBuilder().begin(
            targets, farVisuals(true, 0.2F), 0.045F);
        Assert.assertTrue(culled.advance(null));
        ChainPreviewMesh culledMesh = culled.getMesh();
        Assert.assertEquals("alpha == enter 必须按 <= 剔除", 4, culled.getCulledTargetCount());
        Assert.assertEquals(4, culledMesh.getCulledTargetCount());
        Assert.assertEquals(0, culledMesh.getBlockCount());
        Assert.assertEquals(0, culledMesh.getIndexCount());
        Assert.assertTrue(culledMesh.isEmpty());

        ChainPreviewMesh retained = new ChainPreviewMeshBuilder().build(
            targets, farVisuals(true, 0.19F));
        Assert.assertEquals("alpha 略高于 enter 必须保留", 0, retained.getCulledTargetCount());
        Assert.assertEquals(4, retained.getBlockCount());
        Assert.assertArrayEquals(
            new ChainPreviewMeshBuilder().build(targets, farVisuals(false, 0.0F)).getVertices(),
            retained.getVertices(),
            0.0F);
    }

    @Test
    public void partialCullingKeepsIndicesVerticesAuxConsistentAndRenumbersAppearOrder() {
        int[] carrier = {
            ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.CHAIN_LOCAL,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.TRUNCATED,
            ChainPreviewSemanticClass.EXECUTED,
            ChainPreviewSemanticClass.UNDEFINED,
            ChainPreviewSemanticClass.REMOTE_PREDICTED,
            ChainPreviewSemanticClass.CHAIN_LOCAL,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.TRUNCATED,
            ChainPreviewSemanticClass.EXECUTED,
            ChainPreviewSemanticClass.UNDEFINED,
            ChainPreviewSemanticClass.REMOTE_PREDICTED};

        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            production(scattered(13)), distanceVisuals(true, 0.05F), 0.045F, production(carrier));

        // 相机在原点侧：d=0/2/4 三个目标 alpha > 0.05 保留，其余 10 个 alpha=0 被剔除。
        Assert.assertEquals(10, mesh.getCulledTargetCount());
        Assert.assertEquals(3, mesh.getBlockCount());

        int vertexCount = mesh.getVertexFloatCount() / 3;
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        Assert.assertEquals(vertexCount * ChainPreviewMesh.AUX_BYTES_PER_VERTEX, aux.length);
        Assert.assertEquals(vertexCount * 4, mesh.getColors().length);
        for (int index : mesh.getIndices()) {
            Assert.assertTrue("索引必须指向有效顶点", index >= 0 && index < vertexCount);
        }

        Set<Integer> orders = new HashSet<Integer>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int order = appearOrder(aux, vertex);
            orders.add(Integer.valueOf(order));
            Assert.assertEquals(
                "类别必须与出现序号同源",
                expectedClass(carrier, order),
                aux[vertex * 4] & 0xFF);
        }
        Assert.assertEquals("剔除后 appearOrder 必须连续重编号", 3, orders.size());
        for (int order = 0; order < 3; order++) {
            Assert.assertTrue(orders.contains(Integer.valueOf(order)));
        }

        for (int block = 0; block < 3; block++) {
            Assert.assertTrue("保留目标必须仍有几何 block=" + block, countBlockVertices(mesh, block * 2) > 0);
        }
        for (int block = 3; block < 13; block++) {
            Assert.assertEquals(
                "被剔除目标不得留下任何顶点 block=" + block,
                0,
                countBlockVertices(mesh, block * 2));
        }
    }

    @Test
    public void dualThresholdStopsFlappingNearTheAlphaThreshold() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        // alpha = 1 - (d/10)^2；enter = 0.05，exit = 0.10。
        ChainPreviewMesh culled = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals("alpha<=enter 必须剔除", 1, culled.getCulledTargetCount());

        ChainPreviewMesh between = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(
            "enter<alpha<exit 必须保持已剔除状态（不闪烁）", 1, between.getCulledTargetCount());
        Assert.assertEquals("被剔除目标必须进入滞回记忆", 1, builder.getLodHysteresisMemorySize());

        ChainPreviewMesh restored = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.0D, true, 0.05F));
        Assert.assertEquals("alpha>=exit 必须恢复", 0, restored.getCulledTargetCount());
        Assert.assertEquals(1, restored.getBlockCount());
        Assert.assertEquals("恢复后记忆必须清空该目标", 0, builder.getLodHysteresisMemorySize());

        ChainPreviewMesh fresh = new ChainPreviewMeshBuilder().build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals("无历史记忆时中间区保留", 0, fresh.getCulledTargetCount());

        // 生命周期/换代清理：主线程只置位，构建线程在下一分片消费后才清集合。
        builder.resetLodHysteresis();
        ChainPreviewMesh afterReset = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(
            "置位后下一次构建分片必须消费并清空记忆", 0, afterReset.getCulledTargetCount());
        Assert.assertEquals(0, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void resetRequestIsConsumedByNextBuildShardOnly() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        builder.build(java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        builder.resetLodHysteresis();
        builder.resetLodHysteresis();
        Assert.assertEquals(
            "重复置位幂等：消费前不得改变集合", 1, builder.getLodHysteresisMemorySize());

        // 首次分片开始时消费请求：中间区目标按无记忆判定并保留。
        ChainPreviewMesh consumed = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(0, consumed.getCulledTargetCount());
        Assert.assertEquals(0, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void unrequestedResetKeepsHysteresisAcrossBuilds() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        builder.build(java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // 未置位时不得清理：同一目标在 enter/exit 之间继续保持剔除。
        ChainPreviewMesh again = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(1, again.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void concurrentResetRequestsDoNotCorruptBuildShards() throws Exception {
        final ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        final ChainTarget target = new ChainTarget(0, 0, 0);

        builder.build(java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        final java.util.concurrent.atomic.AtomicBoolean stop =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread requester = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stop.get()) {
                    builder.resetLodHysteresis();
                }
            }
        }, "lod-reset-requester");
        requester.setDaemon(true);
        requester.start();
        try {
            for (int round = 0; round < 200; round++) {
                ChainPreviewMesh mesh = builder.build(
                    java.util.Collections.singletonList(target),
                    hysteresisVisuals(9.9D, true, 0.05F));
                int culled = mesh.getCulledTargetCount();
                Assert.assertTrue("并发置位下构建结果必须仍然合法", culled == 0 || culled == 1);
                Assert.assertEquals("剔除与几何必须一致", culled == 0 ? 1 : 0, mesh.getBlockCount());
            }
        } finally {
            stop.set(true);
            requester.join(5000L);
        }

        // 线程退出后再验证一次：无残留请求、构建仍正常。
        ChainPreviewMesh finalMesh = builder.build(
            java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, finalMesh.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void hysteresisMemoryDoesNotOutliveItsTargetSet() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget farTarget = new ChainTarget(0, 0, 0);

        ChainPreviewMesh firstGeneration = builder.build(
            java.util.Collections.singletonList(farTarget), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, firstGeneration.getCulledTargetCount());
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        // 换代：目标集合不再包含该位置且本轮无剔除 → 记忆必须在构建结束时被裁剪为空。
        // 新目标 (10,0,0) 位于相机附近（相机 x = 10.1），alpha≈0.998，不会被剔除。
        ChainPreviewMesh nextGeneration = builder.build(
            java.util.Collections.singletonList(new ChainTarget(10, 0, 0)),
            hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(0, nextGeneration.getCulledTargetCount());
        Assert.assertEquals("换代后滞回记忆不得残留旧目标", 0, builder.getLodHysteresisMemorySize());

        // 旧位置重新出现且处于 enter/exit 之间时必须重新按当前状态判定（不再受旧记忆影响）。
        ChainPreviewMesh reappeared = builder.build(
            java.util.Collections.singletonList(farTarget), hysteresisVisuals(9.6D, true, 0.05F));
        Assert.assertEquals(0, reappeared.getCulledTargetCount());
    }

    @Test
    public void lodOffNeverTouchesHysteresisMemory() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        builder.build(java.util.Collections.singletonList(target), hysteresisVisuals(9.9D, true, 0.05F));
        Assert.assertEquals(1, builder.getLodHysteresisMemorySize());

        ChainPreviewMesh lodOff = builder.build(
            java.util.Collections.singletonList(new ChainTarget(80, 0, 0)),
            hysteresisVisuals(9.9D, false, 0.05F));
        Assert.assertEquals(0, lodOff.getCulledTargetCount());
        Assert.assertEquals("lod=off 不得改动滞回记忆", 1, builder.getLodHysteresisMemorySize());
    }

    @Test
    public void publicConstructorExposesCulledCountForPlanConsumers() {
        ChainPreviewMesh mesh = new ChainPreviewMesh(
            new float[] {0.0F, 0.0F, 0.0F},
            new float[] {0.25F, 0.9F, 1.0F, 0.8F},
            new int[] {0, 0, 0, 0},
            1,
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            7);
        Assert.assertEquals(7, mesh.getCulledTargetCount());
        Assert.assertTrue(mesh.isAuxAvailable());
    }

    @Test
    public void exitBoundaryEqualityRestoresAlreadyCulledTarget() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        // 远景 alpha == minAlpha == 0.2：enter=0.25 -> 剔除；enter=0.15 -> exit=0.20，等值恢复。
        ChainPreviewMesh culled = builder.build(
            java.util.Collections.singletonList(target), farVisuals(true, 0.25F));
        Assert.assertEquals(1, culled.getCulledTargetCount());

        ChainPreviewMesh boundary = builder.build(
            java.util.Collections.singletonList(target), farVisuals(true, 0.15F));
        Assert.assertEquals(
            "alpha == exit 必须按 >= 恢复", 0, boundary.getCulledTargetCount());
        Assert.assertEquals(1, boundary.getBlockCount());

        ChainPreviewMesh again = builder.build(
            java.util.Collections.singletonList(target), farVisuals(true, 0.25F));
        Assert.assertEquals("回到 enter 之上必须重新剔除", 1, again.getCulledTargetCount());
    }

    @Test
    public void culledCountIsObservableOnSessionAndMesh() {
        BuildSession session = new ChainPreviewMeshBuilder().begin(
            scattered(4), farVisuals(true, 0.2F), 0.045F);
        Assert.assertTrue(session.advance(null));
        Assert.assertEquals(4, session.getCulledTargetCount());
        Assert.assertEquals(4, session.getMesh().getCulledTargetCount());
    }

    /** 远景视觉参数：所有目标中心距离 > fadeEnd，alpha 恒为 minAlpha=0.2。 */
    private static VisualParameters farVisuals(boolean lodEnabled, float enter) {
        return new VisualParameters(
            100.0D, 100.0D, 100.0D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F, lodEnabled, enter);
    }

    /** 相机在近端原点侧：alpha 随距离 quadratic 衰减，minAlpha=0。 */
    private static VisualParameters distanceVisuals(boolean lodEnabled, float enter) {
        return new VisualParameters(
            0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.0F, 0.045F, lodEnabled, enter);
    }

    /** 单目标双阈值夹具：fadeStart=0、fadeEnd=10、maxAlpha=1、minAlpha=0，alpha = 1-(d/10)^2。 */
    private static VisualParameters hysteresisVisuals(double distance, boolean lodEnabled, float enter) {
        return new VisualParameters(
            0.5D + distance, 0.5D, 0.5D, 0.0D, 10.0D, 1.0F, 0.0F, 0.045F, lodEnabled, enter);
    }

    /** 生产快照序（最新→最早）：公共 begin/build 入口的喂入语义（内部翻转为时间序装配）。 */
    private static List<ChainTarget> production(List<ChainTarget> chronological) {
        List<ChainTarget> reversed = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(reversed);
        return reversed;
    }

    /** 与目标一起翻转的类别载体（保持「类别跟随目标」）。 */
    private static int[] production(int[] chronological) {
        int[] reversed = new int[chronological.length];
        for (int index = 0; index < chronological.length; index++) {
            reversed[index] = chronological[chronological.length - 1 - index];
        }
        return reversed;
    }

    private static List<ChainTarget> scattered(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }
        return targets;
    }

    private static int countBlockVertices(ChainPreviewMesh mesh, int worldX) {
        float[] vertices = mesh.getVertices();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX >= worldX - 0.03F && localX <= worldX + 1.03F) {
                found++;
            }
        }
        return found;
    }

    private static int expectedClass(int[] carrier, int order) {
        if (order == ChainPreviewMesh.APPEAR_ORDER_UNDEFINED
                || order < 0
                || order >= carrier.length) {
            return ChainPreviewSemanticClass.UNDEFINED;
        }
        int value = carrier[order];
        if (value >= ChainPreviewSemanticClass.CHAIN_LOCAL
                && value <= ChainPreviewSemanticClass.EXECUTED) {
            return value;
        }
        return ChainPreviewSemanticClass.UNDEFINED;
    }

    private static int appearOrder(byte[] aux, int vertex) {
        return (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
    }
}
