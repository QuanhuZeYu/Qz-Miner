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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * GT 线缆替换模式左键规划入口。
 *
 * <p>阶段8 D1：旧链路 {@code startPlanning} 调用已删除，改为 publish {@link LeftClickObserved}
 * 走新链路 T4 左键入口（与 CHAIN/AREA 的 {@code BlockBreakObserved}、INTERACT 的
 * {@code RightClickObserved} 三事件入口对称），状态机统一推进 ARMED→PLANNING。</p>
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
        if (ChainSubModeRegistry.getTrigger(playerState.getSelectedSubMode()) != ChainSubModeTrigger.LEFT_CLICK_BLOCK) {
            return;
        }

        if (!CompatAdapters.cable().isCable(player.worldObj.getTileEntity(event.x, event.y, event.z))) {
            return;
        }

        // 守 I1：PlayerInteractEvent 在服务端主线程触发；publish 仅入队不切态
        // 阶段8 D1：旧 startPlanning 已删，改为 publish LeftClickObserved 走新链路 T4 第三入口
        // 输入事件 generation 传 0 豁免代际判定
        // hitX/Y/Z 填 0：1.7.10 PlayerInteractEvent 左键分支未暴露命中偏移，GT 线缆 flood fill 不依赖此值
        // sideHit 取 event.face（Forge 1.7.10 PlayerInteractEvent 提供）
        event.setCanceled(true);
        if (MyMod.chainEventBus != null) {
            MyMod.chainEventBus.publish(new LeftClickObserved(
                    player.getUniqueID(), 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension,
                    normalizeFace(event.face),
                    0.0F, 0.0F, 0.0F));
        }
    }

    /**
     * 规范化点击面，避免异常值影响交互执行。
     *
     * @param face 原始点击面
     * @return 合法点击面
     */
    private int normalizeFace(int face) {
        return face < 0 || face > 5 ? 1 : face;
    }
}

