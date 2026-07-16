package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * 仅允许与起点同类且当前可收获的方块匹配器。
 */
public class SameBlockHarvestableMatcher implements ChainBlockMatcher {

    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileEntity sampleTileEntity;
    private final ChainHarvestRules.TargetClassifier classifier;
    private final ChainHarvestRules.HarvestEvaluator harvestEvaluator;
    private final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /**
     * 创建同类方块匹配器。
     *
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     * @param sampleTileEntity 起点 TileEntity
     */
    public SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity) {
        this(sampleBlock, sampleMeta, sampleTileEntity,
                createClassifier(sampleBlock, sampleMeta, sampleTileEntity), ChainHarvestRules.DEFAULT_EVALUATOR, null);
    }

    /** 包级纯判定接缝，供纯 JVM 测试注入不可变策略。 */
    SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity,
            ChainHarvestRules.TargetClassifier classifier, ChainHarvestRules.HarvestEvaluator harvestEvaluator) {
        this(sampleBlock, sampleMeta, sampleTileEntity, classifier, harvestEvaluator, null);
    }

    private SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity,
            ChainHarvestRules.TargetClassifier classifier, ChainHarvestRules.HarvestEvaluator harvestEvaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (classifier == null || harvestEvaluator == null) {
            throw new IllegalArgumentException("classifier and harvestEvaluator must not be null");
        }
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileEntity = sampleTileEntity;
        this.classifier = classifier;
        this.harvestEvaluator = harvestEvaluator;
        this.diagnostics = diagnostics;
    }

    /** 返回绑定 round 诊断器的不可变副本。 */
    SameBlockHarvestableMatcher withDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new SameBlockHarvestableMatcher(sampleBlock, sampleMeta, sampleTileEntity, classifier,
                harvestEvaluator, diagnostics);
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

    /** 创建保持既有 null=false 与身份读取顺序的正式分类器。 */
    private static ChainHarvestRules.TargetClassifier createClassifier(final Block sampleBlock,
            final int sampleMeta, final TileEntity sampleTileEntity) {
        return (player, target) -> player != null && target != null
                && ChainBlockIdentity.matches(player.worldObj, sampleBlock, sampleMeta, sampleTileEntity, target);
    }
}
