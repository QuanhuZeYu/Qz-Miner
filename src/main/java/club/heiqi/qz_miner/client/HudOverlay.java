package club.heiqi.qz_miner.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import org.lwjgl.input.Keyboard;

/**
 * HUD 渲染器。
 *
 * 在屏幕左下角显示当前连锁状态。
 */
@SideOnly(Side.CLIENT)
public class HudOverlay {

    private boolean chainActive = false;

    /**
     * 设置连锁状态是否激活。
     *
     * @param active 是否激活
     */
    public void setChainActive(boolean active) {
        this.chainActive = active;
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

        if (!chainActive) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int x = 4;
        int y = resolution.getScaledHeight() - 20;

        mc.fontRenderer.drawStringWithShadow("\u00a7a\u6b63\u5728\u8fde\u9501", x, y, 0xFFFFFF);
    }
}