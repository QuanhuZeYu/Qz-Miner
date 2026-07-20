package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;

/**
 * 玩家破坏方块被观测到事件。
 */
public final class BlockBreakObserved extends ChainEvent {

    /** 破坏坐标 X。 */
    private final int x;
    /** 破坏坐标 Y。 */
    private final int y;
    /** 破坏坐标 Z。 */
    private final int z;
    /** 所在维度 ID。 */
    private final int dimensionId;
    /** 命中方向（Forge side）。 */
    private final int sideHit;
    /** 破坏时刻捕获的种子方块（drain 时原版已 removeBlock 成空气，必须用此携带值；调试用 null）。 */
    private final Block seedBlock;
    /** 破坏时刻捕获的种子 metadata（与 seedBlock 配对，调试用 0）。 */
    private final int seedMeta;
    /** 破坏时刻在服务端主线程捕获的不可变 TileEntity 身份。 */
    private final TileIdentityToken seedTileIdentity;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param x              坐标 X
     * @param y              坐标 Y
     * @param z              坐标 Z
     * @param dimensionId    维度 ID
     * @param sideHit        命中方向
     * @param seedBlock      破坏时刻捕获的种子方块（drain 时方块已被原版 removeBlock 成空气，必须用此携带值；调试用 null）
     * @param seedMeta       破坏时刻捕获的种子 metadata（与 seedBlock 配对，调试用 0）
     */
    public BlockBreakObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               Block seedBlock, int seedMeta) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, seedBlock, seedMeta, TileIdentityToken.unresolved());
    }

    /** 构造带纯值种子身份的破坏观测事件。 */
    public BlockBreakObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               Block seedBlock, int seedMeta, TileIdentityToken seedTileIdentity) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, seedBlock, seedMeta, seedTileIdentity);
    }

    /** 构造带服务端轮次关联的破坏观测事件。 */
    public BlockBreakObserved(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               Block seedBlock, int seedMeta) {
        this(playerUUID, serverRoundId, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, seedBlock, seedMeta, TileIdentityToken.unresolved());
    }

    /** 构造带服务端轮次和纯值种子身份的破坏观测事件。 */
    public BlockBreakObserved(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               Block seedBlock, int seedMeta, TileIdentityToken seedTileIdentity) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.sideHit = sideHit;
        this.seedBlock = seedBlock;
        this.seedMeta = seedMeta;
        this.seedTileIdentity = seedTileIdentity == null ? TileIdentityToken.unresolved() : seedTileIdentity;
    }

    /** @return 坐标 X */
    public int getX() { return x; }
    /** @return 坐标 Y */
    public int getY() { return y; }
    /** @return 坐标 Z */
    public int getZ() { return z; }
    /** @return 维度 ID */
    public int getDimensionId() { return dimensionId; }
    /** @return 命中方向 */
    public int getSideHit() { return sideHit; }
    /** @return 破坏时刻捕获的种子方块（drain 时方块已被移除，用此携带值；右键/左键路径无此携带） */
    public Block getSeedBlock() { return seedBlock; }
    /** @return 破坏时刻捕获的种子 metadata（与 getSeedBlock() 配对） */
    public int getSeedMeta() { return seedMeta; }
    /** @return 破坏时刻捕获的不可变 TileEntity 身份；旧构造器固定为 UNRESOLVED */
    public TileIdentityToken getSeedTileIdentity() { return seedTileIdentity; }
}
