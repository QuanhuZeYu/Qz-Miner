package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁规划策略。
 */
public interface ChainPlanningStrategy {

    boolean supports(ChainMode mode);

    void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin);
}
