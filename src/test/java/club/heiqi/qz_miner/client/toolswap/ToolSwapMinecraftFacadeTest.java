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
    public void durabilityDamageIsNotSubtypeAndMatcherUsesSharedHarvestGate() throws Exception {
        Item damageable = new Item().setMaxDamage(100);
        Assert.assertEquals(0, ToolSwapMinecraftFacade.stableSubtype(new ItemStack(damageable, 1, 37)));
        TestBlock block = new TestBlock();
        Assert.assertTrue(ToolSwapMinecraftFacade.isEffective(new ItemStack(new TestTool(4.0F)), block, 0));
        String source = source();
        String eligibility = source("src/main/java/club/heiqi/qz_miner/toolswap/ToolHarvestEligibility.java");
        Assert.assertTrue(eligibility.contains("ForgeHooks.canToolHarvestBlock(target, metadata, stack)"));
        Assert.assertTrue(eligibility.contains("target.getMaterial().isToolNotRequired()"));
        Assert.assertTrue("必须直接使用通用 Item API，而不是靠注释命中旧 adapter 名称",
                eligibility.contains("stack.getItem().canHarvestBlock(target, stack)"));
        Assert.assertFalse(eligibility.contains("CompatAdapters"));
        Assert.assertFalse(eligibility.contains("tconstruct"));
        Assert.assertFalse(eligibility.contains("HARVEST_TOOL_TYPE"));
        Assert.assertFalse(eligibility.contains("TConstructToolHarvestCompatAdapter"));
        Assert.assertFalse(eligibility.contains("canToolHarvestBlock(target, metadata, null)"));
        Assert.assertTrue(source.contains("ToolHarvestEligibility.snapshotCandidate"));
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

    /** 未知 Item 的通用收获 API 是真实委托，不依赖生产源码注释断言。 */
    @Test
    public void unknownItemUsesGenericHarvestApiDirectly() {
        CountingHarvestItem item = new CountingHarvestItem();
        Assert.assertTrue(ToolSwapMinecraftFacade.canHarvest(
                new ItemStack(item), new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, item.harvestCalls);
    }

    /** 单槽 getDigSpeed 异常只降级效率事实，且门面仍可继续采样收获事实。 */
    @Test
    public void digSpeedFailureDoesNotAbortFacadeHarvestSampling() throws Exception {
        ItemStack stack = new ItemStack(new ThrowingSpeedHarvestItem());
        Block target = new NullHarvestToolBlock();

        Assert.assertFalse(ToolSwapMinecraftFacade.isEffective(stack, target, 0));
        Assert.assertTrue(ToolSwapMinecraftFacade.canHarvest(stack, target, 0));
        String eligibility = source("src/main/java/club/heiqi/qz_miner/toolswap/ToolHarvestEligibility.java");
        Assert.assertTrue(eligibility.contains("catch (RuntimeException failure)"));
        Assert.assertTrue(eligibility.contains("catch (LinkageError failure)"));
        Assert.assertTrue(source().contains("ToolHarvestEligibility.snapshotCandidate"));
    }

    private static String source() throws Exception {
        return source("src/main/java/club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade.java");
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
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

    private static final class NullHarvestToolBlock extends Block {
        private NullHarvestToolBlock() { super(Material.rock); }
        @Override public String getHarvestTool(int metadata) { return null; }
    }

    private static class CountingHarvestItem extends Item {
        private int harvestCalls;
        private CountingHarvestItem() { setMaxDamage(100); }
        @Override public boolean canHarvestBlock(Block block, ItemStack stack) {
            harvestCalls++;
            return true;
        }
    }

    private static final class ThrowingSpeedHarvestItem extends CountingHarvestItem {
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            throw new IllegalStateException("synthetic optional speed failure");
        }
    }
}
