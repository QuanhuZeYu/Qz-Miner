package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 原木连锁匹配器。
 */
public class LogBlockHarvestableMatcher implements ChainBlockMatcher {

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

        if (!ChainLogRules.isLogBlock(player.worldObj, target)) {
            return false;
        }

        return ChainHarvestRules.canHarvest(player, target, diagnostics);
    }
}
