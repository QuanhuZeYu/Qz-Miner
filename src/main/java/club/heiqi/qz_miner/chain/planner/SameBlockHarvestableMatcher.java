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
    private ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /**
     * 创建同类方块匹配器。
     *
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     * @param sampleTileEntity 起点 TileEntity
     */
    public SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity) {
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileEntity = sampleTileEntity;
    }

    /** 由运行时工厂在 worker 启动前注入 round 级诊断器。 */
    void setDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        if (!ChainBlockIdentity.matches(player.worldObj, sampleBlock, sampleMeta, sampleTileEntity, target,
                diagnostics)) {
            return false;
        }

        return ChainHarvestRules.canHarvest(player, target, diagnostics);
    }
}
