package club.heiqi.qz_miner.toolswap;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemStack;
import tconstruct.library.tools.HarvestTool;

/** 共享采掘资格对无显式工具目标的兼容回归。 */
public class ToolHarvestEligibilityTest {

    /** TiC 旧式工具 API 允许时，无显式 harvestTool 的岩石目标应可采掘。 */
    @Test
    public void tconstructHarvestToolUsesLegacyHarvestApiForNullHarvestTool() {
        ItemStack stack = new ItemStack(new LegacyHarvestTool(true));

        Assert.assertTrue(ToolHarvestEligibility.canHarvest(stack, new NullHarvestToolBlock(), 2));
    }

    /** 非 TiC Item 即便旧式 API 返回 true，也不得获得通用 fallback。 */
    @Test
    public void ordinaryItemDoesNotReceiveLegacyFallback() {
        CountingOrdinaryItem item = new CountingOrdinaryItem(true, 4.0F);

        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(item), new NullHarvestToolBlock(), 2));
        Assert.assertEquals(0, item.harvestCalls);
    }

    /** TiC 旧式 API 的拒绝与异常都必须 fail-closed。 */
    @Test
    public void tconstructDenyAndFailureAreClosed() {
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(new LegacyHarvestTool(false)), new NullHarvestToolBlock(), 0));
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(new ThrowingLegacyHarvestTool()), new NullHarvestToolBlock(), 0));
    }

    /** 显式 harvestTool 始终由 Forge 终裁，不调用可选旧式 API。 */
    @Test
    public void explicitHarvestToolNeverInvokesLegacyFallback() {
        LegacyHarvestTool item = new LegacyHarvestTool(true);
        boolean allowed = false;
        try {
            allowed = ToolHarvestEligibility.canHarvest(
                    new ItemStack(item), new ExplicitHarvestToolBlock(), 0);
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            // 纯 JVM 未启动 Forge 注册表；这里只验证显式路径不回退适配器。
        }

        Assert.assertFalse(allowed);
        Assert.assertEquals(0, item.harvestCalls);
    }

    /** 效率、剩余一耐久与破损门保持短路，并且兼容能力每次只求值一次。 */
    @Test
    public void efficiencyDurabilityAndSingleEvaluationRemainRequired() {
        LegacyHarvestTool slow = new LegacyHarvestTool(true, 1.0F, 100);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                new ItemStack(slow), new NullHarvestToolBlock(), 0));
        Assert.assertEquals(0, slow.harvestCalls);

        LegacyHarvestTool lowReserve = new LegacyHarvestTool(true, 4.0F, 100);
        ItemStack lowReserveStack = new ItemStack(lowReserve, 1, 99);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                lowReserveStack, new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, lowReserve.harvestCalls);

        LegacyHarvestTool broken = new LegacyHarvestTool(true, 4.0F, 100);
        ItemStack brokenStack = new ItemStack(broken, 1, 100);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                brokenStack, new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, broken.harvestCalls);

        LegacyHarvestTool usable = new LegacyHarvestTool(true, 4.0F, 100);
        Assert.assertTrue(ToolHarvestEligibility.isEligible(
                new ItemStack(usable), new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, usable.harvestCalls);
    }

    /** 无效输入保持拒绝，不进入适配器。 */
    @Test
    public void invalidInputsAreRejected() {
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(null, new NullHarvestToolBlock(), 0));
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(new ItemStack(new LegacyHarvestTool(true)), null, 0));
    }

    /** 模拟 Smeltery 一类材质需工具、但未声明 Forge harvestTool 的目标。 */
    private static final class NullHarvestToolBlock extends Block {
        private NullHarvestToolBlock() {
            super(Material.rock);
        }

        @Override
        public String getHarvestTool(int metadata) {
            return null;
        }
    }

    /** 模拟声明了高等级 pickaxe 的 Forge 权威目标。 */
    private static final class ExplicitHarvestToolBlock extends Block {
        private ExplicitHarvestToolBlock() {
            super(Material.rock);
        }

        @Override
        public String getHarvestTool(int metadata) {
            return "pickaxe";
        }
    }

    /** 合成 TiC 工具，由稳定 Item 虚调用提供旧式采掘结论。 */
    private static final class LegacyHarvestTool extends HarvestTool {
        private final boolean harvestable;
        private final float speed;
        private int harvestCalls;

        private LegacyHarvestTool(boolean harvestable) {
            this(harvestable, 4.0F, 0);
        }

        private LegacyHarvestTool(boolean harvestable, float speed, int maxDamage) {
            this.harvestable = harvestable;
            this.speed = speed;
            setMaxDamage(maxDamage);
        }

        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            harvestCalls++;
            return harvestable;
        }

        @Override
        public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            return speed;
        }
    }

    /** 合成旧式调用异常。 */
    private static final class ThrowingLegacyHarvestTool extends HarvestTool {
        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            throw new IllegalStateException("synthetic invocation failure");
        }
    }

    /** 非 TiC 的相同旧式 Item API。 */
    private static final class CountingOrdinaryItem extends net.minecraft.item.Item {
        private final boolean harvestable;
        private final float speed;
        private int harvestCalls;

        private CountingOrdinaryItem(boolean harvestable, float speed) {
            this.harvestable = harvestable;
            this.speed = speed;
        }

        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            harvestCalls++;
            return harvestable;
        }

        @Override
        public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            return speed;
        }
    }
}
