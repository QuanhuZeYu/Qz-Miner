package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.compat.gregtech.GregTechCableCompatHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * GT 线缆替换模式左键规划入口。
 */
public class GregTechCableReplacePlanner {

    public GregTechCableReplacePlanner() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerLeftClickBlock(PlayerInteractEvent event) {
        if (event.entityPlayer == null || !(event.entityPlayer instanceof EntityPlayerMP)) {
            return;
        }
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        if (player instanceof FakePlayer || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!playerState.isChainKeyPressed() || playerState.isExecuting()) {
            return;
        }
        if (playerState.getSelectedMode() != ChainMode.SPECIAL
            || playerState.getSelectedSubMode() != ChainSubMode.SPECIAL_GT_CABLE_REPLACE) {
            return;
        }

        if (!GregTechCableCompatHelper.isCable(player.worldObj.getTileEntity(event.x, event.y, event.z))) {
            return;
        }

        ChainModeDefinition definition = ChainModeRegistry.getDefinition(playerState.getSelectedMode());
        if (definition == null || definition.getPlanningStrategy() == null) {
            return;
        }

        event.setCanceled(true);
        definition.getPlanningStrategy().startPlanning(player, playerState, new ChainTarget(event.x, event.y, event.z));
    }
}
