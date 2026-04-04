package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 默认方块挖掘匹配器。
 */
public class HarvestableBlockMatcher implements ChainBlockMatcher {

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        return ChainHarvestRules.canHarvest(player, target);
    }
}
