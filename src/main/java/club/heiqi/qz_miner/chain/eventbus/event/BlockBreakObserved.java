package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

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
     */
    public BlockBreakObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                              int x, int y, int z, int dimensionId, int sideHit) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.sideHit = sideHit;
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
}
