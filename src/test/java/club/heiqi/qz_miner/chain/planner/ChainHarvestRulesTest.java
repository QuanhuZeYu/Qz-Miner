package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 规划能力门与主线程执行耐久门的分工合同。 */
public class ChainHarvestRulesTest {

    @Test
    public void planningDefersTransientDurabilityWhileExecutionKeepsTwoPointReserve() {
        for (int remaining = 0; remaining <= 2; remaining++) {
            Assert.assertTrue(ChainHarvestRules.acceptsDurabilityForPhase(remaining, true));
        }
        Assert.assertFalse(ChainHarvestRules.acceptsDurabilityForPhase(0, false));
        Assert.assertFalse(ChainHarvestRules.acceptsDurabilityForPhase(1, false));
        Assert.assertTrue(ChainHarvestRules.acceptsDurabilityForPhase(2, false));
        Assert.assertTrue(ChainHarvestRules.acceptsDurabilityForPhase(Integer.MAX_VALUE, false));
    }

    @Test
    public void planningFactoryAndExecutionBridgeUseDifferentHarvestEntries() throws Exception {
        String factory = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java");
        String executor = source("src/main/java/club/heiqi/qz_miner/chain/executor/BlockHarvestActionExecutor.java");
        String rules = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java");

        Assert.assertTrue(factory.contains("ChainHarvestRules.canPlanHarvest(currentPlayer, target, diagnostics)"));
        Assert.assertTrue(executor.contains("ChainHarvestRules.canHarvest(player, target)"));
        Assert.assertFalse(executor.contains("canPlanHarvest"));
        Assert.assertTrue(rules.contains("evaluateHarvest(player, target, diagnosticTracking, false)"));
        Assert.assertTrue(rules.contains("evaluateHarvest(player, target, diagnosticTracking, true)"));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
