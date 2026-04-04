package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;

/**
 * 仅匹配与起点完全相同的方块。
 */
public class SameBlockMatcher implements ChainBlockMatcher {

    private final Block sampleBlock;
    private final int sampleMeta;

    /**
     * 创建同类方块匹配器。
     *
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     */
    public SameBlockMatcher(Block sampleBlock, int sampleMeta) {
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
        return meta == sampleMeta;
    }
}
