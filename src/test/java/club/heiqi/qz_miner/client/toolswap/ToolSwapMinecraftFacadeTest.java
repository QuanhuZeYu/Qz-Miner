package club.heiqi.qz_miner.client.toolswap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Minecraft 事实采样与共享指纹合同。 */
public class ToolSwapMinecraftFacadeTest {

    @Test
    public void facadeOnlySamplesAndRequiresEmptyCursorForSafeProtectedCapture() throws Exception {
        String source = source();
        Assert.assertFalse(source.contains("windowClick"));
        Assert.assertFalse(source.contains("toContainerSlot"));
        Assert.assertFalse(source.contains("connectionIdentity"));
        Assert.assertTrue(source.contains("player.inventory.getItemStack() == null"));
        Assert.assertTrue(source.contains("!light.creative"));
        Assert.assertTrue(source.contains("plan == ToolSwapCapturePlan.PROTECTED"));
    }

    @Test
    public void durabilityDamageIsNotSubtypeAndMatcherUsesForgeHarvestGate() throws Exception {
        Item damageable = new Item().setMaxDamage(100);
        Assert.assertEquals(0, ToolSwapMinecraftFacade.stableSubtype(new ItemStack(damageable, 1, 37)));
        TestBlock block = new TestBlock();
        Assert.assertTrue(ToolSwapMinecraftFacade.isEffective(new ItemStack(new TestTool(4.0F)), block, 0));
        String source = source();
        Assert.assertTrue(source.contains("ForgeHooks.canToolHarvestBlock(target, metadata, stack)"));
        Assert.assertTrue(source.contains("AutoToolSwapStackStateFactory.capture(stack)"));
        Assert.assertTrue(source.contains("plan == ToolSwapCapturePlan.FULL_TARGET"));
        Assert.assertTrue(source.contains("Block.getBlockById(targetIdentity.blockId())"));
        Assert.assertTrue(source.contains("targetBlockMetadata"));
        Assert.assertTrue(source.contains("captureTargetIdentity("));
        String inventoryCapture = source.substring(source.indexOf("ToolSwapInventorySnapshot captureInventory"));
        Assert.assertFalse("普通 FULL 不得二次读取准星", inventoryCapture.contains("objectMouseOver"));
        Assert.assertTrue(inventoryCapture.contains("lightTarget"));
        Assert.assertTrue(source.contains("return null;"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade.java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static final class TestBlock extends Block {
        private TestBlock() { super(Material.rock); }
        @Override public String getHarvestTool(int metadata) { return "pickaxe"; }
    }

    private static final class TestTool extends Item {
        private final float speed;
        private TestTool(float speed) { this.speed = speed; }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) { return speed; }
    }
}
