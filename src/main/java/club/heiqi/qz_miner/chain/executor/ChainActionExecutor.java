package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁动作执行策略。
 */
public interface ChainActionExecutor {

    boolean supports(ChainMode mode);

    boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target);

    boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target);
}
