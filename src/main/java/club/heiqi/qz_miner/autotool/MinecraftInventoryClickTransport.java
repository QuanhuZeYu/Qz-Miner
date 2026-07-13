package club.heiqi.qz_miner.autotool;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;

/** 对 PlayerControllerMP.windowClick 的客户端薄适配。 */
public final class MinecraftInventoryClickTransport implements VanillaInventoryTransactionBridge.ClickTransport {
    @Override public boolean canClick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityClientPlayerMP player = minecraft == null ? null : minecraft.thePlayer;
        return minecraft != null && player != null && minecraft.playerController != null
                && minecraft.currentScreen == null && player.openContainer == player.inventoryContainer
                && player.inventory.getItemStack() == null;
    }

    @Override public void click(int sourceContainerSlot, int anchorHotbarIndex) {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.playerController.windowClick(0, sourceContainerSlot, anchorHotbarIndex, 2, minecraft.thePlayer);
    }
}
