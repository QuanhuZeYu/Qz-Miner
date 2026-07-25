package club.heiqi.qz_miner.toolswap;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 共享采掘资格对无显式工具目标的通用 Item API 回归。 */
public class ToolHarvestEligibilityTest {

    /** 任意 Item 的稳定旧式 API 允许时，无显式 harvestTool 的岩石目标应可采掘。 */
    @Test
    public void unknownItemUsesStableHarvestApiForNullHarvestTool() {
        CountingOrdinaryItem item = new CountingOrdinaryItem(true, 1.0F, 0);
        ItemStack stack = new ItemStack(item);

        Assert.assertTrue(ToolHarvestEligibility.canHarvest(stack, new NullHarvestToolBlock(), 2));
        Assert.assertEquals(1, item.harvestCalls);
    }

    /** 通用 Item API 的拒绝保持 fail-closed。 */
    @Test
    public void unknownItemDenyRemainsClosed() {
        CountingOrdinaryItem item = new CountingOrdinaryItem(false, 4.0F, 0);

        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(item), new NullHarvestToolBlock(), 2));
        Assert.assertEquals(1, item.harvestCalls);
    }

    /** 通用 Item API 异常必须 fail-closed。 */
    @Test
    public void unknownItemFailureIsClosed() {
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(new ThrowingOrdinaryItem()), new NullHarvestToolBlock(), 0));
    }

    /** 显式 harvestTool 始终由 Forge 终裁，不调用 Item API fallback。 */
    @Test
    public void explicitHarvestToolNeverInvokesLegacyFallback() {
        CountingOrdinaryItem item = new CountingOrdinaryItem(true, 4.0F, 0);
        boolean allowed = false;
        try {
            allowed = ToolHarvestEligibility.canHarvest(
                    new ItemStack(item), new ExplicitHarvestToolBlock(), 0);
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            // 纯 JVM 未启动 Forge 注册表；这里只验证显式路径不回退 Item API。
        }

        Assert.assertFalse(allowed);
        Assert.assertEquals(0, item.harvestCalls);
    }

    /** 低效率不再否决；收获结论与剩余耐久仍是硬门。 */
    @Test
    public void lowEfficiencyRemainsCandidateWhileHarvestAndDurabilityAreRequired() {
        CountingOrdinaryItem slow = new CountingOrdinaryItem(true, 1.0F, 100);
        ItemStack slowStack = new ItemStack(slow);
        Assert.assertTrue(ToolHarvestEligibility.isEligible(slowStack, new NullHarvestToolBlock(), 0));
        Assert.assertFalse(ToolHarvestEligibility.isEffective(slowStack, new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, slow.harvestCalls);

        CountingOrdinaryItem lowReserve = new CountingOrdinaryItem(true, 4.0F, 100);
        ItemStack lowReserveStack = new ItemStack(lowReserve, 1, 99);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                lowReserveStack, new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, lowReserve.harvestCalls);

        CountingOrdinaryItem broken = new CountingOrdinaryItem(true, 4.0F, 100);
        ItemStack brokenStack = new ItemStack(broken, 1, 100);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                brokenStack, new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, broken.harvestCalls);

        CountingOrdinaryItem usable = new CountingOrdinaryItem(true, 4.0F, 100);
        Assert.assertTrue(ToolHarvestEligibility.isEligible(
                new ItemStack(usable), new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, usable.harvestCalls);

        CountingOrdinaryItem denied = new CountingOrdinaryItem(false, 4.0F, 100);
        Assert.assertFalse(ToolHarvestEligibility.isEligible(
                new ItemStack(denied), new NullHarvestToolBlock(), 0));
        Assert.assertEquals(1, denied.harvestCalls);
    }

    /** 未知低效率 Item 仍能成为真实换位候选，效率只保留在候选事实中。 */
    @Test
    public void unknownLowEfficiencyItemCanBecomeSwapCandidate() {
        ItemStack stack = new ItemStack(new CountingOrdinaryItem(true, 1.0F, 100));
        Block target = new NullHarvestToolBlock();
        ToolCandidate candidate = new ToolCandidate(3, "unknown:slow-tool", 0,
                Collections.<String>emptyList(), ToolHarvestEligibility.isEffective(stack, target, 0),
                ToolHarvestEligibility.canHarvest(stack, target, 0),
                ToolHarvestEligibility.remainingDurability(stack));

        Assert.assertNotNull(candidate);
        Assert.assertTrue(candidate.isEligibleForSwap());
    }

    /** RuntimeException/LinkageError 只令效率事实为 false，候选的收获与耐久事实仍完整。 */
    @Test
    public void digSpeedFailuresRemainOptionalCandidateFacts() {
        Block target = new NullHarvestToolBlock();
        assertOptionalSpeedFailure(new RuntimeFailingSpeedItem(), target);
        assertOptionalSpeedFailure(new LinkageFailingSpeedItem(), target);
    }

    /** 无效输入保持拒绝，不进入适配器。 */
    @Test
    public void invalidInputsAreRejected() {
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(null, new NullHarvestToolBlock(), 0));
        Assert.assertFalse(ToolHarvestEligibility.canHarvest(
                new ItemStack(new CountingOrdinaryItem(true, 4.0F, 0)), null, 0));
    }

    private static void assertOptionalSpeedFailure(Item item, Block target) {
        ItemStack stack = new ItemStack(item);
        boolean effective = ToolHarvestEligibility.isEffective(stack, target, 0);
        boolean canHarvest = ToolHarvestEligibility.canHarvest(stack, target, 0);
        ToolCandidate candidate = new ToolCandidate(3, "unknown:throwing-speed", 0,
                Collections.<String>emptyList(), effective, canHarvest,
                ToolHarvestEligibility.remainingDurability(stack));
        Assert.assertFalse(effective);
        Assert.assertTrue(canHarvest);
        Assert.assertTrue("效率采样失败不得污染 canHarvest=true 的候选", candidate.isEligibleForSwap());
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

    /** 合成稳定 Item API 调用异常。 */
    private static final class ThrowingOrdinaryItem extends Item {
        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            throw new IllegalStateException("synthetic invocation failure");
        }
    }

    /** 合成可选效率事实的运行时异常。 */
    private static final class RuntimeFailingSpeedItem extends Item {
        private RuntimeFailingSpeedItem() { setMaxDamage(100); }
        @Override public boolean canHarvestBlock(Block block, ItemStack stack) { return true; }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            throw new IllegalStateException("synthetic speed failure");
        }
    }

    /** 合成可选效率事实的链接异常。 */
    private static final class LinkageFailingSpeedItem extends Item {
        private LinkageFailingSpeedItem() { setMaxDamage(100); }
        @Override public boolean canHarvestBlock(Block block, ItemStack stack) { return true; }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            throw new LinkageError("synthetic speed linkage failure");
        }
    }

    /** 未知工具只依赖 Minecraft Item 的稳定虚调用。 */
    private static final class CountingOrdinaryItem extends Item {
        private final boolean harvestable;
        private final float speed;
        private int harvestCalls;

        private CountingOrdinaryItem(boolean harvestable, float speed, int maxDamage) {
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
}
