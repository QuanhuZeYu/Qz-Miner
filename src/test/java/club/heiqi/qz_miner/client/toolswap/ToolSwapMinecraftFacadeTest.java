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

/** Minecraft 事实 matcher、subtype 与槽映射合同。 */
public class ToolSwapMinecraftFacadeTest {

    @Test
    public void hotbarAndMainInventoryUseVanillaContainerMapping() {
        Assert.assertEquals(36, ToolSwapMinecraftFacade.toContainerSlot(0));
        Assert.assertEquals(44, ToolSwapMinecraftFacade.toContainerSlot(8));
        Assert.assertEquals(9, ToolSwapMinecraftFacade.toContainerSlot(9));
        Assert.assertEquals(35, ToolSwapMinecraftFacade.toContainerSlot(35));
    }

    @Test
    public void durabilityDamageIsNotSubtypeButDeclaredSubtypeIsStableIdentity() {
        Item damageable = new Item().setMaxDamage(100);
        ItemStack damaged = new ItemStack(damageable, 1, 37);
        Assert.assertEquals(0, ToolSwapMinecraftFacade.stableSubtype(damaged));

        Item variants = new Item().setHasSubtypes(true);
        ItemStack variant = new ItemStack(variants, 1, 4);
        Assert.assertEquals(4, ToolSwapMinecraftFacade.stableSubtype(variant));
    }

    @Test
    public void candidateRequiresRealEfficiencyAndUsesForgeHarvestGate() throws Exception {
        TestBlock block = new TestBlock();
        ItemStack efficientLowLevel = new ItemStack(new TestTool(4.0F, 1));
        ItemStack harvestOnly = new ItemStack(new TestTool(1.0F, 3));

        Assert.assertTrue(ToolSwapMinecraftFacade.isEffective(efficientLowLevel, block, 0));
        Assert.assertFalse(ToolSwapMinecraftFacade.isEffective(harvestOnly, block, 0));
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue(source.contains("target.getHarvestTool(metadata) == null"));
        Assert.assertTrue(source.contains("ForgeHooks.canToolHarvestBlock(target, metadata, stack)"));
        Assert.assertFalse(source.contains("Block.canHarvestBlock"));
    }

    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }

        @Override
        public String getHarvestTool(int metadata) {
            return "pickaxe";
        }

        @Override
        public int getHarvestLevel(int metadata) {
            return 2;
        }
    }

    private static final class TestTool extends Item {
        private final float speed;
        private final int level;

        private TestTool(float speed, int level) {
            this.speed = speed;
            this.level = level;
        }

        @Override
        public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            return speed;
        }

        @Override
        public int getHarvestLevel(ItemStack stack, String toolClass) {
            return "pickaxe".equals(toolClass) ? level : -1;
        }
    }
}
