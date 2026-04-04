package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 方块可挖掘匹配器。
 */
public interface ChainBlockMatcher {

    boolean matches(EntityPlayer player, ChainTarget target);
}
