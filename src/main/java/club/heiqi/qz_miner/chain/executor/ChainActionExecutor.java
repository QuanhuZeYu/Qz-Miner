package club.heiqi.qz_miner.chain.executor;

import java.util.concurrent.ConcurrentLinkedQueue;

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

    /**
     * 在当前执行队列耗尽后，允许执行器补充后续阶段目标。
     *
     * @param player 当前玩家
     * @param session 当前会话
     * @param queue 当前待执行队列
     * @return 是否补充了新的后续目标
     */
    default boolean enqueueFollowUpTargets(EntityPlayerMP player, ChainSession session, ConcurrentLinkedQueue<ChainTarget> queue) {
        return false;
    }

    /**
     * 是否要求等待规划全部完成后再进入执行阶段。
     */
    default boolean shouldWaitForPlannerCompletion(ChainSession session) {
        return false;
    }
}
