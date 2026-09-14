package club.heiqi.qz_miner.chain.planner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/** 对象组模式安全 decorator 的表驱动测试。 */
public class ModeExtensionMatcherDecoratorTest {

    private static final ChainTarget TARGET = new ChainTarget(1, 2, 3);
    private static final String DECORATOR_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ModeExtensionMatcherDecorator.java";

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
        for (ChainSubMode unsupported : Arrays.asList(
                ChainSubMode.AREA_TUNNEL,
                ChainSubMode.INTERACT_LIQUID_SOURCE,
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP)) {
            Assert.assertSame(unsupported.name(), matcher,
                ModeExtensionMatcherDecorator.decorateMatcher(unsupported, matcher,
                    (player, target) -> true, (player, target) -> true, (player, target) -> true));
            Assert.assertSame(unsupported.name(), filter,
                ModeExtensionMatcherDecorator.decorateCandidateFilter(
                    unsupported, filter, target -> true));
        }
    }

    @Test
    public void emptyCandidateExtensionReturnsOriginalFilter() {
        ChainCandidateFilter filter = target -> false;
        Assert.assertSame(filter, ModeExtensionMatcherDecorator.decorateCandidateFilter(ChainSubMode.CHAIN_BASE,
            filter, new FrozenModePredicate(ModeExtensionSnapshot.EMPTY), null));
    }

    /**
     * 直接装饰器的兼容入口只允许绑定规划宽进门。
     *
     * <p>Lead 裁定第 14 条：改为 token 扫描并去掉对尾随逗号的依赖——断言类内出现的
     * {@code ChainHarvestRules::x} 方法引用集合恰为 {canPlanHarvest}。旧写法依赖
     * {@code "ChainHarvestRules::canHarvest,"} 的尾随逗号，写法一变即失效，
     * 且「换绑另一个门」只在拼写完全一致时才会被发现。</p>
     *
     * <p>已删：{@code selectPlanningEvaluator(mode, capabilitySnapshot)}、
     * {@code boundMatcher, diagnostics, planningEvaluator}、{@code evaluator.evaluate(currentPlayer, target}
     * 三条逐字实参文本快照——同一语义已由 ChainHarvestRulesTest（模式门行为 + 装配接缝必须收到
     * planningEvaluator + 扩展门先求值再落诊断）与 ChainPlanningDiagnosticsTest（原子装配顺序）承担。</p>
     */
    @Test
    public void productionHarvestExtensionUsesTopLevelModeSelectedPlanningGate() {
        String decorator = JavaSourceSlices.stripped(DECORATOR_PATH);
        Assert.assertEquals("直接装饰器的兼容入口只允许绑定规划宽进门",
                Collections.singleton("canPlanHarvest"),
                JavaSourceSlices.identifiersAfter(decorator, "ChainHarvestRules::"));
    }

    private static ChainBlockMatcher matcher(ChainSubMode mode, boolean base, boolean extension, boolean gate) {
        return ModeExtensionMatcherDecorator.decorateMatcher(mode, (player, target) -> base,
            (player, target) -> extension, (player, target) -> gate, (player, target) -> gate);
    }
}
