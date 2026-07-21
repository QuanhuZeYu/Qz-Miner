package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 连锁方块种子快照。
 */
public final class BlockSeedSnapshot {

    private final ChainTarget origin;
    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileIdentityToken sampleTileIdentity;
    /** 仅保留给既有预览/特殊模式适配器；普通服务端 same-block worker 不消费此字段。 */
    private final TileEntity sampleTileEntity;

    /**
     * 既有 live-TE 兼容构造器：构造时立即捕获纯值 token，同时保留特殊模式兼容字段。
     */
    public BlockSeedSnapshot(ChainTarget origin, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity) {
        this(origin, sampleBlock, sampleMeta, CompatAdapters.captureTileIdentity(sampleTileEntity), sampleTileEntity);
    }

    /** 创建不持有 live TileEntity 的纯值种子快照。 */
    public BlockSeedSnapshot(ChainTarget origin, Block sampleBlock, int sampleMeta,
            TileIdentityToken sampleTileIdentity) {
        this(origin, sampleBlock, sampleMeta, sampleTileIdentity, null);
    }

    /** 创建同时携带纯值身份与既有特殊模式兼容字段的快照。 */
    BlockSeedSnapshot(ChainTarget origin, Block sampleBlock, int sampleMeta,
            TileIdentityToken sampleTileIdentity, TileEntity sampleTileEntity) {
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileIdentity = sampleTileIdentity == null ? TileIdentityToken.unresolved() : sampleTileIdentity;
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

    /** @return 主线程捕获的不可变 TileEntity 身份 */
    public TileIdentityToken getSampleTileIdentity() {
        return sampleTileIdentity;
    }

    /** @return 既有预览/特殊模式兼容字段；普通 same-block 匹配不得读取 */
    public TileEntity getSampleTileEntity() {
        return sampleTileEntity;
    }
}
