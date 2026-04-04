package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 连锁方块种子快照。
 */
public final class BlockSeedSnapshot {

    private final ChainTarget origin;
    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileEntity sampleTileEntity;

    public BlockSeedSnapshot(ChainTarget origin, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity) {
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileEntity = sampleTileEntity;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public Block getSampleBlock() {
        return sampleBlock;
    }

    public int getSampleMeta() {
        return sampleMeta;
    }

    public TileEntity getSampleTileEntity() {
        return sampleTileEntity;
    }
}
