package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.LeftClickObserved;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** 主手为 GT 线缆时接管左键动作，发布 LeftClickObserved 交状态机启动线缆规划。 */
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
        if (ChainSubModeRegistry.getTrigger(playerState.getSelectedSubMode()) != ChainSubModeTrigger.LEFT_CLICK_BLOCK) {
            return;
        }

        if (!CompatAdapters.cable().isCable(player.worldObj.getTileEntity(event.x, event.y, event.z))) {
            return;
        }

        // 仅主手线缆取得替换动作授权。
        ItemStack heldStack = player.inventory.getCurrentItem();
        if (!CompatAdapters.cable().isCableStack(heldStack)) {
            return;
        }

        // 主线程接管左键并发布观测；面取 event.face，Forge 1.7.10 缺少左键命中偏移，故偏移填零。
        event.setCanceled(true);
        if (MyMod.chainEventBus != null) {
            long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                    : MyMod.autoToolSwapRoundService.currentRoundId(player.getUniqueID(), player);
            MyMod.chainEventBus.publish(new LeftClickObserved(
                    player.getUniqueID(), serverRoundId, 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension,
                    normalizeFace(event.face),
                    0.0F, 0.0F, 0.0F));
        }
    }

    /**
     * 将异常点击面回退为上表面。
     *
     * @param face 原始点击面
     * @return 合法点击面
     */
    private int normalizeFace(int face) {
        return face < 0 || face > 5 ? 1 : face;
    }
}
