package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * 宽泛矿石匹配器。
 */
public class OreBlockHarvestableMatcher implements ChainBlockMatcher {

    private final ChainHarvestRules.TargetClassifier classifier;
    private final ChainHarvestRules.HarvestEvaluator harvestEvaluator;
    private final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /** 创建绑定正式矿石身份与共享采掘规则的 matcher。 */
    public OreBlockHarvestableMatcher() {
        this(createClassifier(), ChainHarvestRules.DEFAULT_EVALUATOR, null);
    }

    /** 包级纯判定接缝，供纯 JVM 测试注入不可变策略。 */
    OreBlockHarvestableMatcher(ChainHarvestRules.TargetClassifier classifier,
            ChainHarvestRules.HarvestEvaluator harvestEvaluator) {
        this(classifier, harvestEvaluator, null);
    }

    private OreBlockHarvestableMatcher(ChainHarvestRules.TargetClassifier classifier,
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
    OreBlockHarvestableMatcher withDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new OreBlockHarvestableMatcher(classifier, harvestEvaluator, diagnostics);
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

    /** 创建保持既有 null=false 与世界读取顺序的正式分类器。 */
    private static ChainHarvestRules.TargetClassifier createClassifier() {
        return (player, target) -> {
            if (player == null || target == null) {
                return false;
            }
            Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
            TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
            return ChainOreRules.isOreBlock(block, tileEntity);
        };
    }
}
