package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 默认方块挖掘匹配器。
 */
public class HarvestableBlockMatcher implements ChainBlockMatcher {

    private final ChainHarvestRules.HarvestEvaluator harvestEvaluator;
    private final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /** 创建绑定正式共享采掘规则的默认 matcher。 */
    public HarvestableBlockMatcher() {
        this(ChainHarvestRules.DEFAULT_EVALUATOR, null);
    }

    /** 包级纯判定接缝，供纯 JVM 测试注入不可变策略。 */
    HarvestableBlockMatcher(ChainHarvestRules.HarvestEvaluator harvestEvaluator) {
        this(harvestEvaluator, null);
    }

    private HarvestableBlockMatcher(ChainHarvestRules.HarvestEvaluator harvestEvaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (harvestEvaluator == null) {
            throw new IllegalArgumentException("harvestEvaluator must not be null");
        }
        this.harvestEvaluator = harvestEvaluator;
        this.diagnostics = diagnostics;
    }

    /** 返回绑定 round 诊断器的不可变副本。 */
    HarvestableBlockMatcher withDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new HarvestableBlockMatcher(harvestEvaluator, diagnostics);
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        ChainHarvestRules.HarvestEvaluation evaluation = harvestEvaluator.evaluate(player, target,
                diagnostics != null && diagnostics.isTracking(target));
        evaluation.record(diagnostics, target);
        return evaluation.isAccepted();
    }
}
