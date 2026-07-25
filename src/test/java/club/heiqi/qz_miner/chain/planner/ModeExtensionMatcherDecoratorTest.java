package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;

/** 对象组模式安全 decorator 的表驱动测试。 */
public class ModeExtensionMatcherDecoratorTest {

    private static final ChainTarget TARGET = new ChainTarget(1, 2, 3);

    static List<ChainSubMode> supportedModes() {
        return Arrays.asList(ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_ORE, ChainSubMode.CHAIN_LOGGING,
            ChainSubMode.AREA_SAME_BLOCK, ChainSubMode.AREA_ORE,
            ChainSubMode.INTERACT_BASE, ChainSubMode.INTERACT_CROP);
    }

    static List<ChainSubMode> harvestModes() {
        return supportedModes().subList(0, 5);
    }

    @Test
    public void emptyExtensionReturnsOriginalMatcherForSevenModes() {
        ChainBlockMatcher base = (player, target) -> false;
        for (ChainSubMode mode : supportedModes()) {
            Assert.assertSame(base,
                ModeExtensionMatcherDecorator.decorateMatcher(mode, base, ModeExtensionSnapshot.EMPTY));
        }
    }

    @Test
    public void harvestExtensionRequiresModeGateForFiveModes() {
        for (ChainSubMode mode : harvestModes()) {
            Assert.assertFalse(matcher(mode, false, true, false).matches(null, TARGET));
            Assert.assertTrue(matcher(mode, false, true, true).matches(null, TARGET));
        }
    }

    @Test
    public void matcherImplementsQOrGatedXTruthTable() {
        Assert.assertFalse(matcher(ChainSubMode.CHAIN_BASE, false, false, false).matches(null, TARGET));
        Assert.assertFalse(matcher(ChainSubMode.CHAIN_BASE, false, true, false).matches(null, TARGET));
        Assert.assertTrue(matcher(ChainSubMode.CHAIN_BASE, false, true, true).matches(null, TARGET));
        Assert.assertTrue(matcher(ChainSubMode.CHAIN_BASE, true, false, false).matches(null, TARGET));
    }

    @Test
    public void baseMatchShortCircuitsExtensionAndGates() {
        AtomicInteger calls = new AtomicInteger();
        ChainBlockMatcher matcher = ModeExtensionMatcherDecorator.decorateMatcher(ChainSubMode.CHAIN_BASE,
            (player, target) -> true, (player, target) -> { calls.incrementAndGet(); return true; },
            (player, target) -> { calls.incrementAndGet(); return true; },
            (player, target) -> { calls.incrementAndGet(); return true; });
        Assert.assertTrue(matcher.matches(null, TARGET));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void interactBaseDoesNotUseHarvestGate() {
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_BASE, false, true, false).matches(null, TARGET));
    }

    @Test
    public void cropExtensionUsesOnlyCropTargetGate() {
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_CROP, false, true, true).matches(null, TARGET));
        Assert.assertFalse(matcher(ChainSubMode.INTERACT_CROP, false, true, false).matches(null, TARGET));
    }

    @Test
    public void candidateIsQOrXWithoutHarvestGate() {
        ChainCandidateFilter base = target -> false;
        Assert.assertFalse(ModeExtensionMatcherDecorator.decorateCandidateFilter(ChainSubMode.CHAIN_BASE, base,
            target -> false).canTraverse(TARGET));
        Assert.assertTrue(ModeExtensionMatcherDecorator.decorateCandidateFilter(ChainSubMode.CHAIN_BASE, base,
            target -> true).canTraverse(TARGET));
        Assert.assertTrue(ModeExtensionMatcherDecorator.decorateCandidateFilter(ChainSubMode.CHAIN_BASE,
            target -> true, target -> false).canTraverse(TARGET));
    }

    @Test
    public void unsupportedModeFailsClosedWithOriginalInstances() {
        ChainBlockMatcher matcher = (player, target) -> false;
        ChainCandidateFilter filter = target -> false;
        Assert.assertSame(matcher, ModeExtensionMatcherDecorator.decorateMatcher(ChainSubMode.AREA_TUNNEL, matcher,
            (player, target) -> true, (player, target) -> true, (player, target) -> true));
        Assert.assertSame(filter, ModeExtensionMatcherDecorator.decorateCandidateFilter(
            ChainSubMode.AREA_TUNNEL, filter, target -> true));
    }

    @Test
    public void emptyCandidateExtensionReturnsOriginalFilter() {
        ChainCandidateFilter filter = target -> false;
        Assert.assertSame(filter, ModeExtensionMatcherDecorator.decorateCandidateFilter(ChainSubMode.CHAIN_BASE,
            filter, new FrozenModePredicate(ModeExtensionSnapshot.EMPTY), null));
    }

    @Test
    public void productionHarvestExtensionUsesTopLevelModeSelectedPlanningGate() throws Exception {
        String decorator = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ModeExtensionMatcherDecorator.java").toPath()),
                StandardCharsets.UTF_8);
        String factory = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue("直接装饰器的兼容入口保持 AREA 宽进 admission",
                decorator.contains("ChainHarvestRules::canPlanHarvest"));
        Assert.assertFalse(decorator.contains("ChainHarvestRules::canHarvest,"));
        Assert.assertTrue(factory.contains("selectPlanningEvaluator(mode, capabilitySnapshot)"));
        Assert.assertTrue(factory.contains("boundMatcher, diagnostics, planningEvaluator"));
        Assert.assertTrue(factory.contains("evaluator.evaluate(currentPlayer, target"));
    }

    private static ChainBlockMatcher matcher(ChainSubMode mode, boolean base, boolean extension, boolean gate) {
        return ModeExtensionMatcherDecorator.decorateMatcher(mode, (player, target) -> base,
            (player, target) -> extension, (player, target) -> gate, (player, target) -> gate);
    }
}
