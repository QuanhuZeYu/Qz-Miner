package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
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
        ChainModeDefinition modeDefinition = ChainModeRegistry.getDefinition(selectedMode);
        ChainSubMode selectedSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        boolean showAreaInfo = modeDefinition != null && modeDefinition.shouldShowAreaInfo();
        String statusText;
        if (executionStatus == ChainExecutionStatus.RUNNING) {
            statusText = "\u00a7a" + ClientI18n.tr("hud.qz_miner.status.running");
        } else if (executionStatus == ChainExecutionStatus.PLANNING) {
            statusText = "\u00a7e" + ClientI18n.tr("hud.qz_miner.status.planning");
        } else {
            statusText = "\u00a7e" + ClientI18n.tr("hud.qz_miner.status.idle");
        }
        mc.fontRenderer.drawStringWithShadow(statusText, x, y, 0xFFFFFF);

        String modeText = "\u00a77" + ClientI18n.tr("hud.qz_miner.current_mode", ClientI18n.tr(selectedMode.getDisplayNameKey()));
        mc.fontRenderer.drawStringWithShadow(modeText, x, y - 10, 0xFFFFFF);

        int serverMatchedY = y - 20;
        int previewMatchedY = y - 30;
        int areaInfoY = y - 30;
        if (selectedSubMode != null) {
            String subModeText = "\u00a77" + ClientI18n.tr("hud.qz_miner.current_sub_mode", ClientI18n.tr(selectedSubMode.getDisplayNameKey()));
            mc.fontRenderer.drawStringWithShadow(subModeText, x, y - 20, 0xFFFFFF);
            serverMatchedY = y - 30;
            previewMatchedY = y - 40;
            areaInfoY = y - 40;
        }

        String serverMatchedText = "\u00a77" + ClientI18n.tr(
            "hud.qz_miner.server_matched",
            MyMod.chainStateService.getClientState().getServerMatchedTargetCount());
        mc.fontRenderer.drawStringWithShadow(serverMatchedText, x, serverMatchedY, 0xFFFFFF);

        if (ClientProxy.chainPreviewController != null && MyMod.chainStateService != null
            && MyMod.chainStateService.getClientState().isPreviewActive()) {
            ChainPreviewState previewState = ClientProxy.chainPreviewController.getPreviewState();
            String previewSuffix = previewState.isCompleted()
                ? " \u00a7a" + ClientI18n.tr("hud.qz_miner.preview.completed")
                : " \u00a7e" + ClientI18n.tr("hud.qz_miner.preview.calculating");
            String matchedText = "\u00a77" + ClientI18n.tr(
                "hud.qz_miner.preview_matched",
                previewState.getMatchedCount()) + previewSuffix;
            mc.fontRenderer.drawStringWithShadow(matchedText, x, previewMatchedY, 0xFFFFFF);

            if (showAreaInfo) {
                int[] areaDimensions = resolveAreaDimensions(modeDefinition, selectedSubMode);
                int areaBlockCount = areaDimensions[0] * areaDimensions[1] * areaDimensions[2];
                String areaText = "\u00a77" + ClientI18n.tr(
                    "hud.qz_miner.server_area",
                    areaDimensions[0],
                    areaDimensions[1],
                    areaDimensions[2],
                    areaBlockCount);
                mc.fontRenderer.drawStringWithShadow(areaText, x, y - 50, 0xFFFFFF);
            }
        } else if (showAreaInfo) {
            int[] areaDimensions = resolveAreaDimensions(modeDefinition, selectedSubMode);
            int areaBlockCount = areaDimensions[0] * areaDimensions[1] * areaDimensions[2];
            String areaText = "\u00a77" + ClientI18n.tr(
                "hud.qz_miner.server_area",
                areaDimensions[0],
                areaDimensions[1],
                areaDimensions[2],
                areaBlockCount);
            mc.fontRenderer.drawStringWithShadow(areaText, x, areaInfoY, 0xFFFFFF);
        }
    }

    /**
     * 获取 AREA 模式当前应显示的区域尺寸。
     *
     * @param modeDefinition 当前模式定义
     * @param subMode 当前子模式
     * @return 长宽高
     */
    private int[] resolveAreaDimensions(ChainModeDefinition modeDefinition, ChainSubMode subMode) {
        int radius = MyMod.chainStateService.getClientState().getServerChainRadius();
        if (modeDefinition == null) {
            int sideLength = radius * 2 + 1;
            return new int[] {sideLength, sideLength, sideLength};
        }
        int[] dimensions = modeDefinition.resolveAreaDimensions(radius, subMode);
        if (dimensions != null) {
            return dimensions;
        }
        int sideLength = radius * 2 + 1;
        return new int[] {sideLength, sideLength, sideLength};
    }
}
