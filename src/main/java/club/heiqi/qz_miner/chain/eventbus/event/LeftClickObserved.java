package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 玩家左键方块被观测到事件（GT 线缆替换模式专用，T4 第三入口）。
 *
 * <p>守 NORTH_STAR 不变量 I1：不可变事件，所有字段 {@code final}，构造后不可修改，
 * 可安全跨线程传递（Netty IO 线程 publish → 主线程 drain）。</p>
 *
 * <p>阶段8 D1：GT 线缆左键替换观测事件，与 {@link BlockBreakObserved}（CHAIN/AREA 模式）
 * 及 {@link RightClickObserved}（INTERACT 模式）对称——三者共同构成 T4 ARMED→PLANNING 的
 * 三事件入口（见 {@code NORTH_STAR.md} §5 I10）。GT 线缆左键替换必须以左键触发
 * （{@code PlayerInteractEvent.Action.LEFT_CLICK_BLOCK}），原 {@code GregTechCableReplacePlanner}
 * 直接调 {@code startPlanning} 旧链路删除后改 publish 本事件由状态机统一推进。</p>
 *
 * <p>命中偏移 {@code hitX/Y/Z} 字段对称保留：1.7.10 {@code PlayerInteractEvent} 左键分支
 * 未暴露命中偏移，{@code GregTechCableReplacePlanner} publish 时填 0；右键路径才需精确命中点
 * 供 INTERACT 模式 flood fill 方向判定，GT 线缆替换走 flood fill 不依赖命中偏移。</p>
 */
public final class LeftClickObserved extends ChainEvent {

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
    public LeftClickObserved(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                              int x, int y, int z, int dimensionId, int sideHit,
                              float hitX, float hitY, float hitZ) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                x, y, z, dimensionId, sideHit, hitX, hitY, hitZ);
    }

    /** 构造带服务端轮次关联的左键观测事件。 */
    public LeftClickObserved(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                              int x, int y, int z, int dimensionId, int sideHit,
                              float hitX, float hitY, float hitZ) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
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
