package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 空执行策略，占位但不执行实际动作。
 */
public class NoOpActionExecutor implements ChainActionExecutor {

    private final ChainMode mode;

    /**
     * 创建空执行策略。
     *
     * @param mode 绑定模式
     */
    public NoOpActionExecutor(ChainMode mode) {
        this.mode = mode;
    }

    @Override
    public boolean supports(ChainMode mode) {
        return this.mode == mode;
    }

    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        return false;
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        return false;
    }
}
