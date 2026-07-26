package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;

/** CHAIN 冻结能力断链、AREA 宽进与主线程执行权威的分工合同。 */
public class ChainHarvestRulesTest {

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

    @Test
    public void serverAndPreviewCaptureOnlyChainAndExecutionKeepsRealtimeAuthority() throws Exception {
        String factory = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java");
        String bridge = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java");
        String executor = source("src/main/java/club/heiqi/qz_miner/chain/executor/BlockHarvestActionExecutor.java");
        String rules = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java");

        Assert.assertTrue(factory.contains("PlanningToolCapabilitySnapshot.capture("));
        Assert.assertTrue(factory.contains("usesFrozenToolCapabilities(mode)"));
        Assert.assertTrue(factory.contains("planningEvaluator(capabilitySnapshot)"));
        Assert.assertTrue(bridge.contains("PlanningToolCapabilitySnapshot.capture("));
        Assert.assertTrue(bridge.contains("Config.autoToolPrioritySelectors, true"));
        Assert.assertTrue(factory.contains("bindMatcherDiagnostics"));
        Assert.assertTrue(factory.contains("bindMatcherPlanning"));
        Assert.assertTrue(factory.contains("evaluation.record(diagnostics, target)"));
        Assert.assertTrue(executor.contains("ChainHarvestRules.canHarvest(player, target)"));
        Assert.assertFalse(executor.contains("canPlanHarvest"));
        Assert.assertTrue(rules.contains("return evaluatePlanningAdmission(player, target)"));
        Assert.assertTrue(rules.contains("return evaluateExecutionHarvest(player, target, diagnosticTracking)"));

        int planningStart = rules.indexOf("private static HarvestEvaluation evaluatePlanningAdmission");
        int executionStart = rules.indexOf("private static HarvestEvaluation evaluateExecutionHarvest", planningStart);
        Assert.assertTrue(planningStart >= 0 && executionStart > planningStart);
        String planning = rules.substring(planningStart, executionStart);
        Assert.assertFalse(planning.contains("getCurrentEquippedItem"));
        Assert.assertFalse(planning.contains("player.inventory"));
        Assert.assertFalse(planning.contains("canHarvestBlock("));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
