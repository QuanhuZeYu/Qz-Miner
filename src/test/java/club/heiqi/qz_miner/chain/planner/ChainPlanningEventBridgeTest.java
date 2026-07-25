package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContext;

/**
 * {@link ChainPlanningEventBridge} 纯逻辑单测。
 *
 * <p>覆盖 {@link ChainPlanningEventBridge#buildPlanCompleted}、
 * {@link ChainPlanningEventBridge#buildPlanCancelled} 与 runtime-null 取消接缝：
 * 给定 gen + confirmedCount/reason → 构造正确事件，gen 字段一致。</p>
 *
 * <p><b>worker 真链路无法 JVM 覆盖</b>：依赖 {@code worldObj}/player/session 运行时装配，
 * 留 {@code runClient21}/{@code runServer25} 实机验证（见传感层测试约定）。</p>
 */
public class ChainPlanningEventBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000AB");
    private static final long TICK = 99L;
    private static final long NANOS = 424242L;

    /**
     * buildPlanCompleted：给定 gen + confirmedCount → 构造事件字段一致。
     *
     * <p>这是 gen 传递链的纯逻辑锚点：worker 收到的 planningGen 必须原样回填到 PlanCompleted，
     * 状态机据此 genCheck 判定陈旧/匹配。</p>
     */
    @Test
    public void buildPlanCompletedCarriesGenAndCount() {
        int gen = 7;
        int confirmedCount = 128;
        PlanCompleted event = ChainPlanningEventBridge.buildPlanCompleted(PLAYER, gen, TICK, NANOS, confirmedCount);

        Assert.assertEquals(PLAYER, event.getPlayerUUID());
        Assert.assertEquals("gen 必须原样回填（gen 传递链根基）", gen, event.getGeneration());
        Assert.assertEquals(TICK, event.getServerTick());
        Assert.assertEquals(NANOS, event.getTimestampNanos());
        Assert.assertEquals(confirmedCount, event.getTotalTargets());
    }

    /** buildPlanCompleted：gen=0 边界值正确回填。 */
    @Test
    public void buildPlanCompletedZeroGen() {
        PlanCompleted event = ChainPlanningEventBridge.buildPlanCompleted(PLAYER, 0, TICK, NANOS, 0);
        Assert.assertEquals(0, event.getGeneration());
        Assert.assertEquals(0, event.getTotalTargets());
    }

    /** buildPlanCancelled：给定 gen + reason → 构造事件字段一致。 */
    @Test
    public void buildPlanCancelledCarriesGenAndReason() {
        int gen = 3;
        String reason = "shadow-cancel-requested";
        PlanCancelled event = ChainPlanningEventBridge.buildPlanCancelled(PLAYER, gen, TICK, NANOS, reason);

        Assert.assertEquals(PLAYER, event.getPlayerUUID());
        Assert.assertEquals("gen 必须原样回填", gen, event.getGeneration());
        Assert.assertEquals(TICK, event.getServerTick());
        Assert.assertEquals(NANOS, event.getTimestampNanos());
        Assert.assertEquals(reason, event.getReason());
    }

    /** runtime-null 生产接缝必须同时保留冻结轮次、代际与固定诊断原因。 */
    @Test
    public void runtimeNullCancellationCarriesRoundGenAndReason() {
        long serverRoundId = 303L;
        int planningGen = 9;

        PlanCancelled event = ChainPlanningEventBridge.buildRuntimeNullPlanCancelled(
                PLAYER, serverRoundId, planningGen, TICK, NANOS);

        Assert.assertEquals(PLAYER, event.getPlayerUUID());
        Assert.assertEquals("runtime-null 取消不得丢失原工具轮次", serverRoundId, event.getServerRoundId());
        Assert.assertEquals("runtime-null 取消必须保留规划代际", planningGen, event.getGeneration());
        Assert.assertEquals("shadow-runtime-null", event.getReason());
        Assert.assertEquals(TICK, event.getServerTick());
        Assert.assertEquals(NANOS, event.getTimestampNanos());
    }

    /** buildPlanCancelled：null reason 透传不抛异常（取消原因自由文本）。 */
    @Test
    public void buildPlanCancelledNullReason() {
        PlanCancelled event = ChainPlanningEventBridge.buildPlanCancelled(PLAYER, 1, TICK, NANOS, null);
        Assert.assertEquals(1, event.getGeneration());
        Assert.assertNull(event.getReason());
    }

    /**
     * gen 传递链一致性：同一 planningGen 构造的 completed/cancelled 必须携带相同 gen。
     *
     * <p>模拟 worker 在两种终止路径下 gen 字段一致，状态机据此判定本次代际结束。</p>
     */
    @Test
    public void genConsistencyBetweenCompletedAndCancelled() {
        int planningGen = 5;
        PlanCompleted completed = ChainPlanningEventBridge.buildPlanCompleted(PLAYER, planningGen, TICK, NANOS, 64);
        PlanCancelled cancelled = ChainPlanningEventBridge.buildPlanCancelled(PLAYER, planningGen, TICK, NANOS, "race");
        Assert.assertEquals("completed 与 cancelled 同代际 gen 必须一致", completed.getGeneration(), cancelled.getGeneration());
    }

    /** R1 异步结果在 R2 已开始后仍保留被冻结的 R1 轮次。 */
    @Test
    public void planningResultsKeepFrozenRoundInsteadOfLaterRound() {
        long r1 = 101L;
        long r2 = 102L;
        PlanCompleted r1Completed = ChainPlanningEventBridge.buildPlanCompleted(PLAYER, r1, 5, TICK, NANOS, 64);
        PlanCancelled r1Cancelled = ChainPlanningEventBridge.buildPlanCancelled(PLAYER, r1, 5, TICK, NANOS, "late-r1");
        PlanCompleted r2Completed = ChainPlanningEventBridge.buildPlanCompleted(PLAYER, r2, 5, TICK, NANOS, 64);

        Assert.assertEquals("R1 完成结果不得读取 R2", r1, r1Completed.getServerRoundId());
        Assert.assertEquals("R1 取消结果不得读取 R2", r1, r1Cancelled.getServerRoundId());
        Assert.assertEquals(r2, r2Completed.getServerRoundId());
    }

    @Test
    public void workerWiringAttachesSubscriptionAndSilencesExternalCancellation() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue(source.contains("context.attachPlanningSubscription(subscription)"));
        Assert.assertTrue(source.contains("context.isExternalPlanningCancellationRequested()"));
        Assert.assertTrue(source.contains("tryCompletePlanningAndPublish"));
        Assert.assertTrue(source.contains("publishPlanningProgressIfActive"));
        Assert.assertTrue(source.contains("cancelPlanningAndPublishIfActive"));
        Assert.assertFalse("PlanStarted 不得再冻结工具能力用于 admission",
                source.contains("PlanningToolCapabilitySnapshot.capture("));
        int workerStart = source.indexOf("private ParallelTaskResult runShadowSlice(");
        String worker = source.substring(workerStart);
        Assert.assertFalse(worker.contains("player.inventory"));
        Assert.assertFalse(worker.contains("getCurrentEquippedItem()"));
        Assert.assertFalse(worker.contains("canHarvestBlock("));
    }

    /** RuntimeException publication 失败必须转成一次固定原因取消。 */
    @Test
    public void publicationFailureUsesExactlyOneCancellationAndFixedReason() throws Exception {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER, 303L, 9,
                new ConcurrentLinkedQueue<club.heiqi.qz_miner.chain.planner.ChainTarget>(), null);
        AtomicInteger cancellations = new AtomicInteger();
        boolean completed = ChainPlanningEventBridge.tryCompletePlanningOrCancel(context, 12,
                () -> { throw new IllegalStateException("bus failure"); }, cancellations::incrementAndGet);

        Assert.assertFalse(completed);
        Assert.assertFalse(context.isPlanningComplete());
        Assert.assertFalse(context.isCompleted());
        Assert.assertEquals(1, cancellations.get());

        // 生产固定 reason 与 publication 异常路径必须保持可检索且只走一次取消。
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue(source.contains("plan-completion-publication-failed"));
        Assert.assertTrue(source.contains("catch (RuntimeException failure)"));
        Assert.assertTrue(source.contains("catch (LinkageError failure)"));
    }

    /** LinkageError publication 失败与运行时异常共享单次取消合同。 */
    @Test
    public void linkageErrorDuringCompletionPublicationUsesExactlyOneCancellation() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER, 304L, 10,
                new ConcurrentLinkedQueue<club.heiqi.qz_miner.chain.planner.ChainTarget>(), null);
        AtomicInteger cancellations = new AtomicInteger();
        boolean completed = ChainPlanningEventBridge.tryCompletePlanningOrCancel(context, 13,
                () -> { throw new LinkageError("publication linkage failure"); }, cancellations::incrementAndGet);

        Assert.assertFalse(completed);
        Assert.assertFalse(context.isPlanningComplete());
        Assert.assertFalse(context.isCompleted());
        Assert.assertEquals("LinkageError 也只能发布一次取消", 1, cancellations.get());
        Assert.assertFalse(context.cancelPlanningAndPublishIfActive(cancellations::incrementAndGet));
        Assert.assertEquals(1, cancellations.get());
    }

    /** seed 身份不可解析时必须在 worker 登记前固定取消，不能启动规划。 */
    @Test
    public void unresolvedSeedIdentityCancelsBeforeWorkerRegistration() throws Exception {
        PlanCancelled cancelled = ChainPlanningEventBridge.buildUnresolvedSeedIdentityPlanCancelled(
                PLAYER, 305L, 11, TICK, NANOS);
        Assert.assertEquals("shadow-seed-tile-identity-unresolved", cancelled.getReason());
        Assert.assertEquals(305L, cancelled.getServerRoundId());
        Assert.assertEquals(11, cancelled.getGeneration());

        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java").toPath()),
                StandardCharsets.UTF_8);
        int failClosed = source.indexOf("buildUnresolvedSeedIdentityPlanCancelled(");
        int workerRegistration = source.indexOf("executionContextRegistry.put(context)");
        Assert.assertTrue("UNRESOLVED fail-closed 必须先于 worker/context 登记",
                failClosed >= 0 && workerRegistration > failClosed);
    }
}
