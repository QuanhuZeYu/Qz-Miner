package club.heiqi.qz_miner.chain.planner;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.state.ChainRequest;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** CHAIN 冻结能力断链、AREA 宽进与主线程执行权威的分工合同。 */
public class ChainHarvestRulesTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000B2");
    private static final String FACTORY_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java";
    private static final String BRIDGE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java";
    private static final String EXECUTOR_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/executor/BlockHarvestActionExecutor.java";
    private static final String RULES_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java";

    /** 模式/子模式定义是 runtime 装配的前置：纯 JVM 引导，不触碰 FML。 */
    @BeforeClass
    public static void bootstrapDefinitions() {
        ChainSubModeBootstrap.bootstrap();
        ChainModeBootstrap.bootstrap();
    }

    @Test
    public void planningAdmissionUsesOnlyWorldValidityAndStandingSafety() {
        Assert.assertTrue(ChainHarvestRules.acceptsPlanningAdmission(true, false));
        Assert.assertFalse(ChainHarvestRules.acceptsPlanningAdmission(false, false));
        Assert.assertFalse(ChainHarvestRules.acceptsPlanningAdmission(true, true));
    }

    @Test
    public void executionKeepsTwoPointDurabilityReserve() {
        Assert.assertFalse(ChainHarvestRules.acceptsDurabilityForPhase(0, false));
        Assert.assertFalse(ChainHarvestRules.acceptsDurabilityForPhase(1, false));
        Assert.assertTrue(ChainHarvestRules.acceptsDurabilityForPhase(2, false));
        Assert.assertTrue(ChainHarvestRules.acceptsDurabilityForPhase(Integer.MAX_VALUE, false));
    }

    @Test
    public void topLevelModeSelectsFrozenChainEvaluatorAndWideAreaEvaluator() {
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.emptyList(), false);

        Assert.assertTrue(ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(ChainMode.CHAIN));
        Assert.assertFalse(ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(ChainMode.AREA));
        Assert.assertFalse(ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(ChainMode.INTERACT));
        Assert.assertFalse(ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(ChainMode.SPECIAL));
        Assert.assertNotSame(ChainHarvestRules.DEFAULT_EVALUATOR,
                ChainPlanningRuntimeFactory.selectPlanningEvaluator(ChainMode.CHAIN, snapshot));
        Assert.assertSame(ChainHarvestRules.DEFAULT_EVALUATOR,
                ChainPlanningRuntimeFactory.selectPlanningEvaluator(ChainMode.AREA, snapshot));
        Assert.assertSame(ChainHarvestRules.DEFAULT_EVALUATOR,
                ChainPlanningRuntimeFactory.selectPlanningEvaluator(ChainMode.INTERACT, snapshot));
        Assert.assertSame(ChainHarvestRules.DEFAULT_EVALUATOR,
                ChainPlanningRuntimeFactory.selectPlanningEvaluator(ChainMode.SPECIAL, snapshot));
        Assert.assertNull("CHAIN 缺少冻结快照必须 fail-closed",
                ChainPlanningRuntimeFactory.selectPlanningEvaluator(ChainMode.CHAIN, null));
    }

    /**
     * 能力捕获、runtime 装配、执行器与规划入口的模式分工。
     *
     * <p>改造要点：原用例的主体是十几条「源码里有没有这段字符」——{@code contains("PlanningToolCapabilitySnapshot.capture(")}
     * 这类断言在 import、注释、私有 helper 声明处都能命中，且无法证明捕获结果真的进了 runtime 装配。
     * 现在分三层：</p>
     * <ul>
     *   <li><b>行为</b>：反射调用私有装配接缝 {@code captureToolCapabilitiesForMode} 与
     *       {@code createRuntime}，用真实快照对象断言「CHAIN 捕获且缺快照 fail-closed / AREA 不捕获也不依赖快照」；</li>
     *   <li><b>数据流</b>：事件桥里捕获到的快照变量必须出现在 {@code createForServer} 的实参区间内
     *       （改名不误报，改传 null 会红）；</li>
     *   <li><b>边界清单</b>：执行器只走执行期判定、规划 admission 只走规划评估器，
     *       全部限定在方法体/类区间内（去注释后扫描）。</li>
     * </ul>
     */
    @Test
    public void serverAndPreviewCaptureOnlyChainAndExecutionKeepsRealtimeAuthority() throws Exception {
        ChainSession chainSession = session(ChainMode.CHAIN, ChainSubMode.CHAIN_BASE);
        ChainSession areaSession = session(ChainMode.AREA, ChainSubMode.AREA_SAME_BLOCK);

        Method capture = ChainPlanningRuntimeFactory.class.getDeclaredMethod(
                "captureToolCapabilitiesForMode", EntityPlayer.class, ChainSession.class);
        capture.setAccessible(true);
        Assert.assertNotNull("CHAIN 会话必须在装配期捕获冻结能力快照",
                capture.invoke(null, null, chainSession));
        Assert.assertNull("AREA 会话不得捕获冻结能力（保持实时权威）",
                capture.invoke(null, null, areaSession));

        Method createRuntime = ChainPlanningRuntimeFactory.class.getDeclaredMethod("createRuntime",
                EntityPlayer.class, ChainSession.class, ChainSearchContext.class, ChainMode.class,
                ChainPlanningRuntimeFactory.PlanningDiagnostics.class, PlanningToolCapabilitySnapshot.class);
        createRuntime.setAccessible(true);
        Assert.assertNull("CHAIN 缺冻结快照必须 fail-closed", createRuntime.invoke(null, null, chainSession,
                searchContext(ChainSubMode.CHAIN_BASE), ChainMode.CHAIN, null, null));

        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>emptyList(), false);
        ChainPlanningRuntime chainRuntime = (ChainPlanningRuntime) createRuntime.invoke(null, null, chainSession,
                searchContext(ChainSubMode.CHAIN_BASE), ChainMode.CHAIN, null, snapshot);
        Assert.assertNotNull("CHAIN 持有冻结快照必须装配出 runtime", chainRuntime);
        Assert.assertNotNull("CHAIN runtime 必须绑定 matcher", chainRuntime.getMatcher());
        Assert.assertNotNull("AREA 不依赖冻结快照", createRuntime.invoke(null, null, areaSession,
                searchContext(ChainSubMode.AREA_SAME_BLOCK), ChainMode.AREA, null, null));

        String executor = JavaSourceSlices.stripped(EXECUTOR_PATH);
        String canExecute = JavaSourceSlices.methodBody(executor, "public boolean canExecute(",
                "BlockHarvestActionExecutor.canExecute");
        JavaSourceSlices.assertContains(canExecute, "ChainHarvestRules.canHarvest(",
                "执行期只能走执行期判定");
        JavaSourceSlices.assertAbsent(executor, "canPlanHarvest", "执行器不得引用规划宽进门");
        Assert.assertFalse("null 玩家不得放行执行",
                new BlockHarvestActionExecutor().canExecute(null, null, new ChainTarget(1, 2, 3)));

        String rules = JavaSourceSlices.stripped(RULES_PATH);
        int publicOverload = rules.indexOf("static boolean canPlanHarvest(");
        String publicEntry = JavaSourceSlices.methodBody(rules, "static boolean canPlanHarvest(",
                "ChainHarvestRules.canPlanHarvest(2 参)");
        String planningEntry = JavaSourceSlices.methodBody(rules, "static boolean canPlanHarvest(",
                publicOverload + 1, "ChainHarvestRules.canPlanHarvest(3 参)");
        JavaSourceSlices.assertContains(publicEntry, "canPlanHarvest(",
                "公开规划入口必须委托给带诊断的实现");
        JavaSourceSlices.assertContains(planningEntry, "DEFAULT_EVALUATOR",
                "规划入口必须走规划评估器");
        JavaSourceSlices.assertAbsent(planningEntry, "EXECUTION_EVALUATOR",
                "规划入口不得落到执行期评估器");
        String defaultEvaluator = JavaSourceSlices.methodBody(rules, "static final HarvestEvaluator DEFAULT_EVALUATOR",
                "ChainHarvestRules.DEFAULT_EVALUATOR");
        JavaSourceSlices.assertContains(defaultEvaluator, "evaluatePlanningAdmission(",
                "规划评估器必须落到规划 admission");
        JavaSourceSlices.assertAbsent(defaultEvaluator, "evaluateExecutionHarvest(",
                "规划评估器不得落到执行期判定");

        String admission = JavaSourceSlices.methodBody(rules,
                "private static HarvestEvaluation evaluatePlanningAdmission(", "evaluatePlanningAdmission");
        JavaSourceSlices.assertAbsent(admission, "getCurrentEquippedItem", "规划 admission 不得读手持工具");
        JavaSourceSlices.assertAbsent(admission, "player.inventory", "规划 admission 不得读背包");
        JavaSourceSlices.assertAbsent(admission, "canHarvestBlock(", "规划 admission 不得做采掘能力判定");

        String factory = JavaSourceSlices.stripped(FACTORY_PATH);
        String extensionMatcher = JavaSourceSlices.methodBody(factory,
                "private static ChainBlockMatcher decorateModeExtensionMatcher(", "decorateModeExtensionMatcher");
        JavaSourceSlices.assertBefore(extensionMatcher, "evaluator.evaluate(", "evaluation.record(",
                "扩展门必须先求值再落诊断");

        String onPlanStarted = JavaSourceSlices.methodBody(JavaSourceSlices.stripped(BRIDGE_PATH),
                "private void onPlanStarted(", "ChainPlanningEventBridge.onPlanStarted");
        int captureAt = onPlanStarted.indexOf("PlanningToolCapabilitySnapshot.capture(");
        Assert.assertTrue("事件桥必须在 PlanStarted 捕获冻结能力", captureAt >= 0);
        String capturedSnapshot = JavaSourceSlices.assignmentTarget(onPlanStarted, captureAt, "事件桥能力捕获");
        String runtimeArguments = JavaSourceSlices.callArguments(onPlanStarted,
                "ChainPlanningRuntimeFactory.createForServer(", "事件桥 runtime 装配");
        JavaSourceSlices.assertContains(runtimeArguments, capturedSnapshot,
                "createForServer 必须收到捕获到的冻结快照 " + capturedSnapshot);
    }

    private static ChainSession session(ChainMode mode, ChainSubMode subMode) {
        return new ChainSession(new ChainRequest(PLAYER, mode, subMode, new ChainTarget(0, 0, 0)));
    }

    private static ChainSearchContext searchContext(ChainSubMode subMode) {
        return new ChainSearchContext(null, new ChainTarget(0, 0, 0), null, 0, null, subMode, 4, 8,
                new ConcurrentLinkedQueue<ChainTarget>(), new ConcurrentLinkedQueue<ChainTarget>(),
                Collections.<ChainTarget>emptySet());
    }
}
