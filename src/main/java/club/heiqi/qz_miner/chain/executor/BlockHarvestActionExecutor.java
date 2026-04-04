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
            player.theItemInWorldManager.tryHarvestBlock(target.getX(), target.getY(), target.getZ());
            return true;
        } catch (Exception e) {
            MyMod.LOG.error("[ChainExecutor] Failed to harvest block for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
            return false;
        }
    }
}
