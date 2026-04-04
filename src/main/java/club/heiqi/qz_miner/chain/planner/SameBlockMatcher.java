package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * 仅匹配与起点完全相同的方块。
 */
public class SameBlockMatcher implements ChainBlockMatcher {

    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileEntity sampleTileEntity;

    /**
     * 创建同类方块匹配器。
     *
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     * @param sampleTileEntity 起点 TileEntity
     */
    public SameBlockMatcher(Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity) {
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileEntity = sampleTileEntity;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        return ChainBlockIdentity.matches(player.worldObj, sampleBlock, sampleMeta, sampleTileEntity, target);
    }
}
