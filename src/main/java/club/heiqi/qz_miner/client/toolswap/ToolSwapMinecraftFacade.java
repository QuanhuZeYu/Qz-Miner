package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/** Minecraft 客户端轻量事实门面；严格 5.3 路径从不扫描库存或准星目标。 */
@SideOnly(Side.CLIENT)
public class ToolSwapMinecraftFacade implements AutoToolSwapClientAdapter.GameFacade {

    @Override
    public ToolSwapLightContext captureLightContext(long tick, boolean chainActive) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft == null ? null : minecraft.thePlayer;
        if (minecraft == null || player == null || minecraft.theWorld == null) return null;
        try {
            ChainSubMode selected = MyMod.chainStateService == null
                    ? null : MyMod.chainStateService.getClientState().getSelectedSubMode();
            boolean breakCapable = ChainSubModeRegistry.getTrigger(selected) == ChainSubModeTrigger.BREAK_BLOCK;
            return new ToolSwapLightContext(tick, breakCapable, player.capabilities.isCreativeMode,
                    minecraft.currentScreen != null, chainActive, player.inventory.currentItem);
        } catch (RuntimeException failure) {
            return null;
        } catch (LinkageError failure) {
            return null;
        }
    }

    @Override
    public boolean isChainKeyPhysicallyDown() {
        return KeyListener.chainSwitch != null && KeyListener.chainSwitch.getIsKeyPressed();
    }
}
