package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import net.minecraft.block.Block;

/**
 * 连锁规划开始事件。
 *
 * <p>携带规划启动上下文（origin/dimension/sideHit/hitOffset），供 {@code ChainPlanningEventBridge}
 * 在阶段 4 拿到 {@code generation} + 上下文后发起影子 traverser。本事件由 {@code ChainStateMachine}
 * 在 T4 ARMED→PLANNING 转移完成后作为进态广播 publish（阶段 4 起）。</p>
 *
 * <p>守 NORTH_STAR 不变量 I1：不可变事件，所有字段 {@code final}，构造后不可修改，
 * 可安全跨线程传递（主线程 drain publish → worker 线程读取上下文）。</p>
 *
 * <p>字段集与 {@link RightClickObserved} 对齐：破坏路径（{@code BlockBreakObserved}）无命中偏移，
 * 故 hitX/Y/Z 填 0；右键路径填实际值供 INTERACT 模式 flood fill 方向判定。</p>
 */
public final class PlanStarted extends ChainEvent {

    /** 连锁起点坐标 X。 */
    private final int x;
    /** 连锁起点坐标 Y。 */
    private final int y;
    /** 连锁起点坐标 Z。 */
    private final int z;
    /** 所在维度 ID。 */
    private final int dimensionId;
    /** 命中方向（Forge side，0-5）。 */
    private final int sideHit;
    /** 命中方块内 X 偏移（0-1），破坏路径填 0。 */
    private final float hitX;
    /** 命中方块内 Y 偏移（0-1），破坏路径填 0。 */
    private final float hitY;
    /** 命中方块内 Z 偏移（0-1），破坏路径填 0。 */
    private final float hitZ;
    /** 种子方块：破坏路径透传自 BlockBreakObserved（破坏时刻捕获，drain 时方块已被移除需用此携带值）；右键/左键路径块仍在世界，传 null。 */
    private final Block seedBlock;
    /** 种子 metadata：与 seedBlock 配对，破坏路径透传；右键/左键路径传 0。 */
    private final int seedMeta;

    /**
     * @param playerUUID     触发玩家 UUID
     * @param generation     所属连锁代际（状态机 T4 自增后的新代际）
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param x              起点坐标 X
     * @param y              起点坐标 Y
     * @param z              起点坐标 Z
     * @param dimensionId    维度 ID
     * @param sideHit        命中方向（0-5）
     * @param hitX           命中方块内 X 偏移（破坏路径填 0）
     * @param hitY           命中方块内 Y 偏移（破坏路径填 0）
     * @param hitZ           命中方块内 Z 偏移（破坏路径填 0）
     * @param seedBlock      种子方块：破坏路径透传自 BlockBreakObserved（破坏时刻捕获，drain 时方块已被移除需用此携带值）；右键/左键路径块仍在世界走 resolver，传 null
     * @param seedMeta       种子 metadata：与 seedBlock 配对，破坏路径透传；右键/左键路径传 0
     */
    public PlanStarted(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                       int x, int y, int z, int dimensionId, int sideHit,
                       float hitX, float hitY, float hitZ,
                       Block seedBlock, int seedMeta) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.sideHit = sideHit;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.seedBlock = seedBlock;
        this.seedMeta = seedMeta;
    }

    /** @return 起点坐标 X */
    public int getX() { return x; }
    /** @return 起点坐标 Y */
    public int getY() { return y; }
    /** @return 起点坐标 Z */
    public int getZ() { return z; }
    /** @return 维度 ID */
    public int getDimensionId() { return dimensionId; }
    /** @return 命中方向（0-5） */
    public int getSideHit() { return sideHit; }
    /** @return 命中方块内 X 偏移（破坏路径为 0） */
    public float getHitX() { return hitX; }
    /** @return 命中方块内 Y 偏移（破坏路径为 0） */
    public float getHitY() { return hitY; }
    /** @return 命中方块内 Z 偏移（破坏路径为 0） */
    public float getHitZ() { return hitZ; }
    /** @return 种子方块（破坏路径透传自 BlockBreakObserved；右键/左键路径返回 null） */
    public Block getSeedBlock() { return seedBlock; }
    /** @return 种子 metadata（与 getSeedBlock() 配对） */
    public int getSeedMeta() { return seedMeta; }
}
