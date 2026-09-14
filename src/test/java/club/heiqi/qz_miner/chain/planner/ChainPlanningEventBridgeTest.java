package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContext;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

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
    private static final String BRIDGE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java";
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

    /**
     * worker 装配与活性契约。
     *
     * <p>改造口径（Lead 裁定第 10 条与「去脆化 = methodBody 切片 + 标识符位置」）：
     * 原用例在整文件里找标识符/整句表达式，声明处、注释或文件后面任何位置都能命中。
     * 现在每条断言都钉在<b>真正承载它的方法体</b>里，并按相对位置表达活性/顺序契约。</p>
     */
    @Test
    public void workerWiringAttachesSubscriptionAndSilencesExternalCancellation() {
        String source = JavaSourceSlices.stripped(BRIDGE_PATH);

        String planStarted = JavaSourceSlices.methodBody(source, "private void onPlanStarted(",
                "ChainPlanningEventBridge.onPlanStarted");
        JavaSourceSlices.assertBefore(planStarted, "registerPre(", "attachPlanningSubscription(",
                "订阅必须在 worker 注册成功后挂到 context");
        JavaSourceSlices.assertBefore(planStarted, "usesFrozenToolCapabilities(",
                "PlanningToolCapabilitySnapshot.capture(", "PlanStarted 必须只按顶层 CHAIN 冻结能力");

        String worker = JavaSourceSlices.methodBody(source, "private ParallelTaskResult runShadowSlice(",
                "ChainPlanningEventBridge.runShadowSlice");
        JavaSourceSlices.assertContains(worker, "isExternalPlanningCancellationRequested()",
                "worker 分片必须检查外部取消");
        JavaSourceSlices.assertContains(worker, "tryCompletePlanningOrCancel(",
                "完成路径必须经单次线性化接缝发布");
        // 无 durable progress 的 YIELDED 不得给 watchdog 续命：PlanProgress 发布必须在「进度已进阶」守卫之后。
        JavaSourceSlices.assertBefore(worker, "getProgressRevision() != progressRevisionBefore",
                "publishPlanningProgressIfActive(", "进度未进阶不得发布 PlanProgress");

        String cancellation = JavaSourceSlices.methodBody(source, "private void publishWorkerCancellation(",
                "ChainPlanningEventBridge.publishWorkerCancellation");
        JavaSourceSlices.assertContains(cancellation, "cancelPlanningAndPublishIfActive(",
                "worker 自然取消必须只在仍活跃时发布");

        // 边界清单：worker 分片的能力必须来自 PlanStarted 冻结快照，不得回读背包/手持/采掘能力。
        JavaSourceSlices.assertAbsent(worker, "player.inventory", "worker 分片不得读背包");
        JavaSourceSlices.assertAbsent(worker, "getCurrentEquippedItem()", "worker 分片不得读手持");
        JavaSourceSlices.assertAbsent(worker, "canHarvestBlock(", "worker 分片不得做采掘能力判定");

        // 已删（同批改造）：tryCompletePlanningAndPublish / publishPlanningProgressIfActive /
        // cancelPlanningAndPublishIfActive 的纯标识符存在性各自并入上面语义更强的方法体断言；
        // Config.autoToolPrioritySelectors, true 与 diagnostics, capabilitySnapshot 两条逐字实参快照删除——
        // 「冻结快照必须进 createForServer」已由 ChainHarvestRulesTest 的数据流断言（捕获变量必须出现在实参区间）承担。
    }

    /** RuntimeException publication 失败必须转成一次固定原因取消。 */
    @Test
    public void publicationFailureUsesExactlyOneCancellationAndFixedReason() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER, 303L, 9,
                new ConcurrentLinkedQueue<club.heiqi.qz_miner.chain.planner.ChainTarget>(), null);
        AtomicInteger cancellations = new AtomicInteger();
        boolean completed = ChainPlanningEventBridge.tryCompletePlanningOrCancel(context, 12,
                () -> { throw new IllegalStateException("bus failure"); }, cancellations::incrementAndGet);

        Assert.assertFalse(completed);
        Assert.assertFalse(context.isPlanningComplete());
        Assert.assertFalse(context.isCompleted());
        Assert.assertEquals(1, cancellations.get());

        // 固定 reason 是日志/事件可检索的诊断片段（不是协议字段），因此只锚定「取消发布确实带上了它」，
        // 不锚定整句文案（Lead 裁定第 2 条口径）：先在 worker 完成路径里定位该字面量的赋值目标，
        // 再要求同一变量出现在 buildPlanCancelled 的实参区间内。
        String worker = JavaSourceSlices.methodBody(JavaSourceSlices.stripped(BRIDGE_PATH),
                "private ParallelTaskResult runShadowSlice(", "ChainPlanningEventBridge.runShadowSlice");
        int fixedReason = worker.indexOf("\"plan-completion-publication-failed\"");
        Assert.assertTrue("完成失败路径必须使用固定诊断 reason", fixedReason >= 0);
        String reasonVariable = JavaSourceSlices.assignmentTarget(worker, fixedReason, "固定 reason 赋值");
        String cancellationArguments = JavaSourceSlices.callArguments(worker, "buildPlanCancelled(",
                "完成失败路径的取消发布");
        JavaSourceSlices.assertContains(cancellationArguments, reasonVariable,
                "取消事件必须携带固定诊断 reason " + reasonVariable);

        // 已删：catch (RuntimeException failure) / catch (LinkageError failure) 两条捕获样式文本快照——
        // 两条异常路径的行为已分别由本用例与 linkageErrorDuringCompletionPublicationUsesExactlyOneCancellation
        // 经真实 tryCompletePlanningOrCancel 调用证伪（不再捕获时异常会直接抛出，测试必红）。
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
    public void unresolvedSeedIdentityCancelsBeforeWorkerRegistration() {
        PlanCancelled cancelled = ChainPlanningEventBridge.buildUnresolvedSeedIdentityPlanCancelled(
                PLAYER, 305L, 11, TICK, NANOS);
        Assert.assertEquals("shadow-seed-tile-identity-unresolved", cancelled.getReason());
        Assert.assertEquals(305L, cancelled.getServerRoundId());
        Assert.assertEquals(11, cancelled.getGeneration());

        String planStarted = JavaSourceSlices.methodBody(JavaSourceSlices.stripped(BRIDGE_PATH),
                "private void onPlanStarted(", "ChainPlanningEventBridge.onPlanStarted");
        JavaSourceSlices.assertBefore(planStarted, "buildUnresolvedSeedIdentityPlanCancelled(",
                "executionContextRegistry.put(", "UNRESOLVED fail-closed 必须先于 worker/context 登记");
    }
}
