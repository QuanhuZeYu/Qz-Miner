package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;

/**
 * HUD 渲染器。
 *
 * 在屏幕左下角显示当前连锁状态。
 */
@SideOnly(Side.CLIENT)
public class HudOverlay {

    /**
     * 设置连锁状态是否激活。
     *
     * @param active 是否激活
     */
    public void setChainActive(boolean active) {
    }

    /**
     * 注册事件监听。
     */
    public void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != ElementType.TEXT) {
            return;
        }

        if (MyMod.chainStateService == null || !MyMod.chainStateService.getClientState().isChainActiveDisplay()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int x = 4;
        int y = resolution.getScaledHeight() - 20;

        ChainExecutionStatus executionStatus = MyMod.chainStateService.getClientState().getServerExecutionStatus();
        ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        String statusText;
        if (executionStatus == ChainExecutionStatus.RUNNING) {
            statusText = "\u00a7a\u6b63\u5728\u8fde\u9501";
        } else if (executionStatus == ChainExecutionStatus.PLANNING) {
            statusText = "\u00a7e\u8fde\u9501\u89c4\u5212\u4e2d";
        } else {
            statusText = "\u00a7e\u8fde\u9501\u5f85\u547d";
        }
        mc.fontRenderer.drawStringWithShadow(statusText, x, y, 0xFFFFFF);

        String modeText = "\u00a77\u5f53\u524d\u6a21\u5f0f: " + selectedMode.name();
        mc.fontRenderer.drawStringWithShadow(modeText, x, y - 10, 0xFFFFFF);

        String serverMatchedText = String.format(
            "\u00a77\u670d\u52a1\u7aef\u5df2\u5339\u914d: %d \u4e2a\u65b9\u5757",
            MyMod.chainStateService.getClientState().getServerMatchedTargetCount());
        mc.fontRenderer.drawStringWithShadow(serverMatchedText, x, y - 20, 0xFFFFFF);

        if (ClientProxy.chainPreviewController != null && MyMod.chainStateService != null
            && MyMod.chainStateService.getClientState().isPreviewActive()) {
            ChainPreviewState previewState = ClientProxy.chainPreviewController.getPreviewState();
            String matchedText = String.format(
                "\u00a77\u9884\u89c8\u5df2\u5339\u914d: %d \u4e2a\u65b9\u5757%s",
                previewState.getMatchedCount(),
                previewState.isCompleted() ? " \u00a7a(\u5b8c\u6210)" : " \u00a7e(\u8ba1\u7b97\u4e2d)");
            mc.fontRenderer.drawStringWithShadow(matchedText, x, y - 30, 0xFFFFFF);

            if (selectedMode == ChainMode.AREA) {
                int sideLength = MyMod.chainStateService.getClientState().getServerChainRadius() * 2 + 1;
                int areaBlockCount = sideLength * sideLength * sideLength;
                String areaText = String.format(
                    "\u00a77\u670d\u52a1\u7aef\u533a\u57df: %d x %d x %d = %d",
                    sideLength,
                    sideLength,
                    sideLength,
                    areaBlockCount);
                mc.fontRenderer.drawStringWithShadow(areaText, x, y - 40, 0xFFFFFF);
            }
        } else if (selectedMode == ChainMode.AREA) {
            int sideLength = MyMod.chainStateService.getClientState().getServerChainRadius() * 2 + 1;
            int areaBlockCount = sideLength * sideLength * sideLength;
            String areaText = String.format(
                "\u00a77\u670d\u52a1\u7aef\u533a\u57df: %d x %d x %d = %d",
                sideLength,
                sideLength,
                sideLength,
                areaBlockCount);
            mc.fontRenderer.drawStringWithShadow(areaText, x, y - 30, 0xFFFFFF);
        }
    }
}
