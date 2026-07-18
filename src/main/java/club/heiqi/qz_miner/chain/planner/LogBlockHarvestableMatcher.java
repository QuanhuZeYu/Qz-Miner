package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 原木连锁匹配器。
 */
public class LogBlockHarvestableMatcher implements ChainBlockMatcher {

    private final ChainHarvestRules.TargetClassifier classifier;
    private final ChainHarvestRules.HarvestEvaluator harvestEvaluator;
    private final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /** 创建绑定正式原木身份与共享采掘规则的 matcher。 */
    public LogBlockHarvestableMatcher() {
        this(createClassifier(), ChainHarvestRules.DEFAULT_EVALUATOR, null);
    }

    /** 包级纯判定接缝，供纯 JVM 测试注入不可变策略。 */
    LogBlockHarvestableMatcher(ChainHarvestRules.TargetClassifier classifier,
            ChainHarvestRules.HarvestEvaluator harvestEvaluator) {
        this(classifier, harvestEvaluator, null);
    }

    private LogBlockHarvestableMatcher(ChainHarvestRules.TargetClassifier classifier,
            ChainHarvestRules.HarvestEvaluator harvestEvaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (classifier == null || harvestEvaluator == null) {
            throw new IllegalArgumentException("classifier and harvestEvaluator must not be null");
        }
        this.classifier = classifier;
        this.harvestEvaluator = harvestEvaluator;
        this.diagnostics = diagnostics;
    }

    /** 返回绑定 round 诊断器的不可变副本。 */
    LogBlockHarvestableMatcher withDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new LogBlockHarvestableMatcher(classifier, harvestEvaluator, diagnostics);
    }

    /** 返回同时绑定冻结 round evaluator 与诊断器的不可变副本。 */
    LogBlockHarvestableMatcher withPlanningEvaluator(ChainHarvestRules.HarvestEvaluator evaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new LogBlockHarvestableMatcher(classifier, evaluator, diagnostics);
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (!classifier.matches(player, target)) {
            return false;
        }

        ChainHarvestRules.HarvestEvaluation evaluation = harvestEvaluator.evaluate(player, target,
                diagnostics != null && diagnostics.isTracking(target));
        evaluation.record(diagnostics, target);
        return evaluation.isAccepted();
    }

    /** 创建保持既有 null=false 与原木读取顺序的正式分类器。 */
    private static ChainHarvestRules.TargetClassifier createClassifier() {
        return (player, target) -> player != null && target != null
                && ChainLogRules.isLogBlock(player.worldObj, target);
    }
}
