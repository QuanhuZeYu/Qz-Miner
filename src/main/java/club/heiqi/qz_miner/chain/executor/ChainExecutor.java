package club.heiqi.qz_miner.chain.executor;

import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁执行器调度器。
 */
public class ChainExecutor {

    public ChainExecutor() {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || MyMod.chainStateService == null || MyMod.playerManager == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            if (playerState == null) {
                continue;
            }

            if (playerState.getExecutionStatus() == ChainExecutionStatus.IDLE) {
                continue;
            }

            ChainSession session = playerState.getSession();
            if (session == null) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "missing-session");
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "player-unavailable");
                continue;
            }

            if (!playerState.isChainKeyPressed()) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "key-released");
                continue;
            }

            ConcurrentLinkedQueue<ChainTarget> queue = session.getPendingBreakTargets();
            if (!session.isExecutorReady(nowMillis)) {
                continue;
            }

            if (session.getRequest() == null) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "missing-request");
                continue;
            }

            ChainModeDefinition definition = ChainModeRegistry.getDefinition(session.getRequest().getMode());
            if (definition == null || definition.getActionExecutor() == null) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "missing-action-executor");
                continue;
            }
            ChainSubMode subMode = session.getRequest() == null ? null : session.getRequest().getSubMode();
            ChainActionExecutor actionExecutor = definition.resolveActionExecutor(subMode);
            if (actionExecutor == null) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "missing-sub-mode-action-executor");
                continue;
            }

            if (actionExecutor.shouldWaitForPlannerCompletion(session) && !session.isPlannerCompleted()) {
                continue;
            }

            int maxBreakPerTick = Config.maxBreakPerTick;
            int executedCount = 0;

            while (executedCount < maxBreakPerTick) {
                ChainTarget target = queue.poll();
                if (target == null) {
                    break;
                }

                if (!actionExecutor.canExecute((EntityPlayerMP) player, session, target)) {
                    continue;
                }

                if (!actionExecutor.execute((EntityPlayerMP) player, session, target)) {
                    continue;
                }

                executedCount++;
            }

            if (executedCount > 0) {
                session.scheduleNextExecutorRun(nowMillis, 50L);
            }

            if (queue.isEmpty() && actionExecutor.enqueueFollowUpTargets((EntityPlayerMP) player, session, queue)) {
                session.scheduleNextExecutorRun(nowMillis, 0L);
            }

            if (queue.isEmpty() && session.isPlannerCompleted()) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "executor-consumed-all-targets");
            }
        }
    }

}
