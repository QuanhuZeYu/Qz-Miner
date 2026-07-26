package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

/** 服务端 Minecraft 库存适配的结构边界。 */
public class MinecraftAutoToolSwapInventoryPortStructureTest {

    /** 适配必须分离直接 mutation 与可重复原版完整库存同步。 */
    @Test
    public void portUsesOnlyAllowedInventoryAndSynchronizationWiring() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/toolswap/server/MinecraftAutoToolSwapInventoryPort.java").toPath()),
                StandardCharsets.UTF_8);

        Assert.assertTrue(source.contains("player.openContainer == player.inventoryContainer"));
        Assert.assertTrue(source.contains("player.inventoryContainer.windowId == 0"));
        Assert.assertTrue(source.contains("player.inventory.getItemStack() == null"));
        Assert.assertTrue(source.contains("player.inventory.mainInventory"));
        Assert.assertTrue(source.contains("player.inventory.markDirty()"));
        Assert.assertTrue(source.contains("player.sendContainerToPlayer(player.inventoryContainer)"));
        Assert.assertFalse(source.contains("player.inventoryContainer.detectAndSendChanges()"));
        int swapMethod = source.indexOf("void swapInventorySlotsAtomically");
        int rotateMethod = source.indexOf("void rotateInventorySlotsAtomically");
        int syncMethod = source.indexOf("void syncInventoryDifference");
        Assert.assertFalse(source.substring(swapMethod, rotateMethod).contains("markDirty()"));
        Assert.assertFalse(source.substring(rotateMethod, syncMethod).contains("markDirty()"));
        for (String forbidden : Arrays.asList("slotClick", "windowClick", "S2F", "currentItem =")) {
            Assert.assertFalse("forbidden inventory path: " + forbidden, source.contains(forbidden));
        }
    }
}
