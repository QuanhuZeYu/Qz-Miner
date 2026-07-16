package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * 宽泛矿石匹配器。
 */
public class OreBlockHarvestableMatcher implements ChainBlockMatcher {

    private ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /** 由运行时工厂在 worker 启动前注入 round 级诊断器。 */
    void setDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!ChainOreRules.isOreBlock(block, tileEntity)) {
            return false;
        }

        return ChainHarvestRules.canHarvest(player, target, diagnostics);
    }
}
