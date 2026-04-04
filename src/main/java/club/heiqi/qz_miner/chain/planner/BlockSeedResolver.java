package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁方块种子解析器。
 */
public interface BlockSeedResolver {

    BlockSeedSnapshot resolve(EntityPlayerMP player, ChainTarget origin);
}
