package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;

/**
 * 连锁方块种子快照。
 */
public final class BlockSeedSnapshot {

    private final ChainTarget origin;
    private final Block sampleBlock;
    private final int sampleMeta;

    public BlockSeedSnapshot(ChainTarget origin, Block sampleBlock, int sampleMeta) {
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
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
}
