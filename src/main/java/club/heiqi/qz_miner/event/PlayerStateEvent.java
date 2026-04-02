package club.heiqi.qz_miner.event;

import net.minecraft.entity.player.EntityPlayer;

/**
 * 玩家状态变更事件。
 *
 * 当玩家的关键状态发生变化时触发（加入、离开、重生、维度切换、克隆等）。
 * 其他模块可以监听此事件来同步玩家状态。
 */
public class PlayerStateEvent implements Event {

    /**
     * 状态变更的原因。
     */
    public enum Reason {
        /**
         * 玩家加入游戏。
         */
        LOGIN,
        /**
         * 玩家离开游戏。
         */
        LOGOUT,
        /**
         * 玩家重生。
         */
        RESPAWN,
        /**
         * 玩家切换维度。
         */
        DIMENSION_CHANGE,
        /**
         * 玩家克隆（死亡重生或维度切换时的数据复制）。
         */
        CLONE
    }

    /**
     * 发生状态变更的玩家。
     */
    public final EntityPlayer player;

    /**
     * 状态变更的原因。
     */
    public final Reason reason;

    /**
     * 创建玩家状态变更事件。
     *
     * @param player 玩家
     * @param reason 变更原因
     */
    public PlayerStateEvent(EntityPlayer player, Reason reason) {
        this.player = player;
        this.reason = reason;
    }
}