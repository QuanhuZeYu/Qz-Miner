package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * 仅允许与起点同类且当前可收获的方块匹配器。
 */
public class SameBlockHarvestableMatcher implements ChainBlockMatcher {

    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileIdentityToken sampleTileIdentity;
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
        this(sampleBlock, sampleMeta, CompatAdapters.captureTileIdentity(sampleTileEntity));
    }

    /** 创建使用主线程冻结纯值身份的同类可采掘匹配器。 */
    public SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileIdentityToken sampleTileIdentity) {
        this(sampleBlock, sampleMeta, sampleTileIdentity,
                createClassifier(sampleBlock, sampleMeta, sampleTileIdentity), ChainHarvestRules.DEFAULT_EVALUATOR, null);
    }

    /** 包级纯判定接缝，供纯 JVM 测试注入不可变策略。 */
    SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity,
            ChainHarvestRules.TargetClassifier classifier, ChainHarvestRules.HarvestEvaluator harvestEvaluator) {
        this(sampleBlock, sampleMeta, CompatAdapters.captureTileIdentity(sampleTileEntity),
                classifier, harvestEvaluator, null);
    }

    private SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileIdentityToken sampleTileIdentity,
            ChainHarvestRules.TargetClassifier classifier, ChainHarvestRules.HarvestEvaluator harvestEvaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (classifier == null || harvestEvaluator == null) {
            throw new IllegalArgumentException("classifier and harvestEvaluator must not be null");
        }
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileIdentity = sampleTileIdentity == null ? TileIdentityToken.unresolved() : sampleTileIdentity;
        this.classifier = classifier;
        this.harvestEvaluator = harvestEvaluator;
        this.diagnostics = diagnostics;
    }

    /** 返回绑定 round 诊断器的不可变副本。 */
    SameBlockHarvestableMatcher withDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new SameBlockHarvestableMatcher(sampleBlock, sampleMeta, sampleTileIdentity, classifier,
                harvestEvaluator, diagnostics);
    }

    /** 返回同时绑定冻结 round evaluator 与诊断器的不可变副本。 */
    SameBlockHarvestableMatcher withPlanningEvaluator(ChainHarvestRules.HarvestEvaluator evaluator,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return new SameBlockHarvestableMatcher(sampleBlock, sampleMeta, sampleTileIdentity, classifier,
                evaluator, diagnostics);
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
            final int sampleMeta, final TileIdentityToken sampleTileIdentity) {
        return (player, target) -> player != null && target != null
                && ChainBlockIdentity.matches(player.worldObj, sampleBlock, sampleMeta, sampleTileIdentity, target);
    }
}
