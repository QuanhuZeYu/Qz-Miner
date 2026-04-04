package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 空规划策略，占位但不执行实际搜索。
 */
public class NoOpPlanningStrategy implements ChainPlanningStrategy {

    private final ChainMode mode;

    /**
     * 创建空规划策略。
     *
     * @param mode 绑定模式
     */
    public NoOpPlanningStrategy(ChainMode mode) {
        this.mode = mode;
    }

    @Override
    public boolean supports(ChainMode mode) {
        return this.mode == mode;
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        MyMod.LOG.debug("[ChainPlanner] No-op planning strategy invoked for mode {}", mode);
    }
}
