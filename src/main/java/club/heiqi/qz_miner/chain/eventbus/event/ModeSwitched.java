package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 连锁模式切换事件。
 *
 * <p>引用 {@link ChainMode}/{@link ChainSubMode} 纯数据枚举，不触碰执行链路类。</p>
 */
public final class ModeSwitched extends ChainEvent {

    /** 切换后的主模式。 */
    private final ChainMode newMode;
    /** 切换后的子模式。 */
    private final ChainSubMode newSubMode;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param newMode        新主模式
     * @param newSubMode     新子模式
     */
    public ModeSwitched(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                        ChainMode newMode, ChainSubMode newSubMode) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos, newMode, newSubMode);
    }

    /** 构造带服务端轮次关联的模式切换事件。 */
    public ModeSwitched(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                        ChainMode newMode, ChainSubMode newSubMode) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.newMode = newMode;
        this.newSubMode = newSubMode;
    }

    /** @return 新主模式 */
    public ChainMode getNewMode() { return newMode; }
    /** @return 新子模式 */
    public ChainSubMode getNewSubMode() { return newSubMode; }
}
