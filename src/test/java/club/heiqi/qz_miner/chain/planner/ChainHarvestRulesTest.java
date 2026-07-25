package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 规划宽进 admission 与主线程执行权威的分工合同。 */
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
    public void serverAndPreviewPlannerDoNotCaptureOrBindFrozenToolCapability() throws Exception {
        String factory = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java");
        String bridge = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java");
        String executor = source("src/main/java/club/heiqi/qz_miner/chain/executor/BlockHarvestActionExecutor.java");
        String rules = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java");

        Assert.assertFalse(factory.contains("PlanningToolCapabilitySnapshot"));
        Assert.assertFalse(factory.contains("planningEvaluator("));
        Assert.assertFalse(bridge.contains("PlanningToolCapabilitySnapshot.capture("));
        Assert.assertTrue(factory.contains("bindMatcherDiagnostics"));
        Assert.assertFalse(factory.contains("bindMatcherPlanning"));
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
