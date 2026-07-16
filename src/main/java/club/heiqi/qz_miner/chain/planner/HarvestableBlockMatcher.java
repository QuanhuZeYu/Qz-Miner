package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 默认方块挖掘匹配器。
 */
public class HarvestableBlockMatcher implements ChainBlockMatcher {

    private ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics;

    /** 由运行时工厂在 worker 启动前注入 round 级诊断器。 */
    void setDiagnostics(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        return ChainHarvestRules.canHarvest(player, target, diagnostics);
    }
}
