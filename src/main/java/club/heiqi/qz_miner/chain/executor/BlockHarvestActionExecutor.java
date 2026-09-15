package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainHarvestRules;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 默认的方块挖掘执行策略。
 */
public class BlockHarvestActionExecutor implements ChainActionExecutor {

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.CHAIN || mode == ChainMode.AREA;
    }

    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        return ChainHarvestRules.canHarvest(player, target);
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        try {
            // Issue #242：覆盖口径按「实测增量」结算，故基线必须夹住同一次 tryHarvestBlock——
            // 破坏前的 exhaustion 在这里采集，破坏成功后再按配置值补齐差额。
            float exhaustionBefore = HarvestExhaustionSettlement.captureExhaustion(player);
            boolean harvested = player.theItemInWorldManager
                    .tryHarvestBlock(target.getX(), target.getY(), target.getZ());
            if (harvested) {
                HarvestExhaustionSettlement.settleAfterSuccessfulHarvest(player, exhaustionBefore);
            }
            return harvested;
        } catch (Exception e) {
            MyMod.LOG.error("[BlockHarvestActionExecutor] Failed to harvest block for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
            return false;
        }
    }
}
