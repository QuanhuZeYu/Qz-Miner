package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 原木连锁匹配器。
 */
public class LogBlockHarvestableMatcher implements ChainBlockMatcher {

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        if (!ChainLogRules.isLogBlock(player.worldObj, target)) {
            return false;
        }

        return ChainHarvestRules.canHarvest(player, target);
    }
}
