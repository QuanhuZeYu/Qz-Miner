package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;

/**
 * 玩家右键方块被观测到事件。
 *
 * <p>守 NORTH_STAR 不变量 I1：不可变事件，所有字段 {@code final}，构造后不可修改，
 * 可安全跨线程传递（Netty IO 线程 publish → 主线程 drain）。</p>
 *
 * <p>命中偏移 {@code hitX/Y/Z} 携带是关键——右键触发连锁需要精确命中点供 INTERACT 模式
 * flood fill 方向判定，是 T4 扩右键观测双触发的根因（见 {@code NORTH_STAR.md} §5 I10）。</p>
 */
public final class RightClickObserved extends ChainEvent {

    /** 目标方块坐标 X。 */
    private final int x;
    /** 目标方块坐标 Y。 */
    private final int y;
    /** 目标方块坐标 Z。 */
    private final int z;
    /** 所在维度 ID。 */
    private final int dimensionId;
    /** 命中方向（Forge side，0-5）。 */
    private final int sideHit;
    /** 命中方块内 X 偏移（0-1）。 */
    private final float hitX;
    /** 命中方块内 Y 偏移（0-1）。 */
    private final float hitY;
    /** 命中方块内 Z 偏移（0-1）。 */
    private final float hitZ;
    /** 原右键事件窗口冻结的种子方块；旧构造器无该事实时为 null。 */
    private final Block seedBlock;
    /** 原右键事件窗口冻结的完整非负 int metadata。 */
    private final int seedMeta;
    /** 原右键事件窗口冻结的不可变 TileEntity 身份。 */
    private final TileIdentityToken seedTileIdentity;

    /**
     * @param playerUUID     触发玩家 UUID
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param x              坐标 X
     * @param y              坐标 Y
     * @param z              坐标 Z
     * @param dimensionId    维度 ID
     * @param sideHit        命中方向（0-5）
     * @param hitX           命中方块内 X 偏移
     * @param hitY           命中方块内 Y 偏移
     * @param hitZ           命中方块内 Z 偏移
     */
    public RightClickObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               float hitX, float hitY, float hitZ) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, hitX, hitY, hitZ,
                null, 0, TileIdentityToken.unresolved());
    }

    /** 构造带原事件窗口纯值种子事实的右键观测事件。 */
    public RightClickObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               float hitX, float hitY, float hitZ,
                               Block seedBlock, int seedMeta, TileIdentityToken seedTileIdentity) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, hitX, hitY, hitZ,
                seedBlock, seedMeta, seedTileIdentity);
    }

    /** 构造带服务端轮次关联的右键观测事件。 */
    public RightClickObserved(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               float hitX, float hitY, float hitZ) {
        this(playerUUID, serverRoundId, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, hitX, hitY, hitZ,
                null, 0, TileIdentityToken.unresolved());
    }

    /** 构造带服务端轮次和原事件窗口纯值种子事实的右键观测事件。 */
    public RightClickObserved(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                               int x, int y, int z, int dimensionId, int sideHit,
                               float hitX, float hitY, float hitZ,
                               Block seedBlock, int seedMeta, TileIdentityToken seedTileIdentity) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.sideHit = sideHit;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.seedBlock = seedBlock;
        this.seedMeta = Math.max(0, seedMeta);
        this.seedTileIdentity = seedTileIdentity == null
                ? TileIdentityToken.unresolved() : seedTileIdentity;
    }

    /** @return 坐标 X */
    public int getX() { return x; }
    /** @return 坐标 Y */
    public int getY() { return y; }
    /** @return 坐标 Z */
    public int getZ() { return z; }
    /** @return 维度 ID */
    public int getDimensionId() { return dimensionId; }
    /** @return 命中方向（0-5） */
    public int getSideHit() { return sideHit; }
    /** @return 命中方块内 X 偏移 */
    public float getHitX() { return hitX; }
    /** @return 命中方块内 Y 偏移 */
    public float getHitY() { return hitY; }
    /** @return 命中方块内 Z 偏移 */
    public float getHitZ() { return hitZ; }
    /** @return 原右键事件窗口冻结的种子方块；旧构造器返回 null */
    public Block getSeedBlock() { return seedBlock; }
    /** @return 原右键事件窗口冻结的完整非负 int metadata */
    public int getSeedMeta() { return seedMeta; }
    /** @return 原右键事件窗口冻结的不可变身份；旧构造器固定为 UNRESOLVED */
    public TileIdentityToken getSeedTileIdentity() { return seedTileIdentity; }
}
