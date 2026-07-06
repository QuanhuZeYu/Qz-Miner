package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

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
        super(playerUUID, generation, serverTick, timestampNanos);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.sideHit = sideHit;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
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
}