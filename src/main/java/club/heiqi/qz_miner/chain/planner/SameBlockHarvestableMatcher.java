package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;

/**
 * 仅允许与起点同类且当前可收获的方块匹配器。
 */
public class SameBlockHarvestableMatcher implements ChainBlockMatcher {

    private final Block sampleBlock;
    private final int sampleMeta;

    /**
     * 创建同类方块匹配器。
     *
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     */
    public SameBlockHarvestableMatcher(Block sampleBlock, int sampleMeta) {
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block != sampleBlock) {
            return false;
        }

        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        if (meta != sampleMeta) {
            return false;
        }

        return ChainHarvestRules.canHarvest(player, target);
    }
}
