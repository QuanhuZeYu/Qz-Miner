package club.heiqi.qz_miner.chain.planner;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.qz_miner.toolswap.ToolHarvestEligibility;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 冻结规划能力的候选顺序、空手语义与库存隔离合同。 */
public class PlanningToolCapabilitySnapshotTest {

    @Test
    public void currentHandThenInventoryThenEmptyHandAreDistinctPriorities() {
        Block harvestable = new TestBlock(Material.wood);
        Block toolRequired = new TestBlock(Material.rock);
        ItemStack heldTool = new ItemStack(new TestTool());
        ItemStack inventoryTool = new ItemStack(new TestTool());
        PlanningToolCapabilitySnapshot held = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                heldTool, Collections.singletonList(inventoryTool), false);
        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.CURRENT_HAND,
                held.select(harvestable, 0));

        PlanningToolCapabilitySnapshot backpack = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.singletonList(inventoryTool), false);
        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.INVENTORY_TOOL,
                backpack.select(harvestable, 0));
        PlanningToolCapabilitySnapshot empty = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>emptyList(), false);
        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.EMPTY_HAND,
                empty.select(harvestable, 0));

        PlanningToolCapabilitySnapshot noInventory = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>emptyList(), false);
        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.NONE,
                noInventory.select(toolRequired, 0));
    }

    @Test
    public void capturedStacksAreIsolatedFromLaterInventoryDamageAndReplacement() {
        ItemStack original = new ItemStack(new TestTool());
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.singletonList(original), false);

        original.setItemDamage(original.getMaxDamage() - 1);

        Assert.assertEquals(1, snapshot.inventoryToolCount());
        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.INVENTORY_TOOL,
                snapshot.select(new TestBlock(Material.wood), 0));
    }

    /** 冻结 CHAIN 规划与客户端候选共用未知 Item 的 null-harvestTool 资格入口。 */
    @Test
    public void unknownNullHarvestToolCapabilityIsFrozenThroughSharedEligibility() {
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>singletonList(new ItemStack(new GenericHarvestTool(false))), false);

        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.INVENTORY_TOOL,
                snapshot.select(new NullHarvestToolBlock(), 2));
    }

    /** 效率采样异常不属于冻结能力硬门，不能否决 canHarvest=true 的工具。 */
    @Test
    public void digSpeedFailureDoesNotRemoveFrozenHarvestCapability() {
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>singletonList(new ItemStack(new GenericHarvestTool(true))), false);

        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.INVENTORY_TOOL,
                snapshot.select(new NullHarvestToolBlock(), 2));
    }

    /**
     * 冻结快照的候选来源、共享资格入口与 worker 侧零库存读取。
     *
     * <p>改造口径：{@code select()} 与共享资格规则的一致性改为<b>行为断言</b>——
     * 同一 Block/ItemStack 同时驱动 {@code snapshot.select} 与
     * {@link ToolHarvestEligibility#isEligible}，断言二者结论逐例相等（判据不是「源码里调用了谁」）；
     * 「候选必须经共享 selector 排序」保留为 {@code capture(ItemStack[], ...)} 方法体内的委派契约；
     * worker 侧禁读清单从「到文件尾的 substring」收敛为
     * {@code evaluateFrozenPlanningHarvest} 方法体区间。</p>
     *
     * <p>已实测：{@code capture(ItemStack[], ...)} 的 {@code includeInventoryTools=true} 分支在纯 JVM
     * 不可达（{@code OreDictionary} 触达 FMLRelaunchLog 静态初始化，抛
     * ExceptionInInitializerError: FMLRelaunchLog.side is null），故该分支不做行为断言。</p>
     */
    @Test
    public void productionSnapshotUsesSelectorOrderAndSharedEligibilityWithoutWorkerInventoryReads() {
        assertSelectFollowsSharedEligibility(new NullHarvestToolBlock(), 2,
                new ItemStack(new GenericHarvestTool(false)));
        assertSelectFollowsSharedEligibility(new NullHarvestToolBlock(), 2, new ItemStack(new TestTool()));
        ItemStack nearlyBroken = new ItemStack(new GenericHarvestTool(false));
        nearlyBroken.setItemDamage(nearlyBroken.getMaxDamage() - 1);
        assertSelectFollowsSharedEligibility(new NullHarvestToolBlock(), 2, nearlyBroken);

        String snapshot = JavaSourceSlices.stripped(
                "src/main/java/club/heiqi/qz_miner/chain/planner/PlanningToolCapabilitySnapshot.java");
        int arrayCapture = snapshot.indexOf("static PlanningToolCapabilitySnapshot capture(") + 1;
        String captureBody = JavaSourceSlices.methodBody(snapshot,
                "static PlanningToolCapabilitySnapshot capture(", arrayCapture,
                "PlanningToolCapabilitySnapshot.capture(ItemStack[], ...)");
        JavaSourceSlices.assertContains(captureBody, "ToolCandidateOrder.sort(",
                "背包候选必须经共享 selector 排序入口");
        JavaSourceSlices.assertContains(captureBody, "ToolHarvestEligibility.snapshotIdentity(",
                "候选身份必须来自共享资格采样的纯值快照");

        String rules = JavaSourceSlices.stripped(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java");
        String frozenEvaluator = JavaSourceSlices.methodBody(rules,
                "private static HarvestEvaluation evaluateFrozenPlanningHarvest(",
                "ChainHarvestRules.evaluateFrozenPlanningHarvest");
        JavaSourceSlices.assertAbsent(frozenEvaluator, "getCurrentEquippedItem",
                "冻结规划路径不得读手持工具");
        JavaSourceSlices.assertAbsent(frozenEvaluator, "player.inventory",
                "冻结规划路径不得读背包");

        // 已删：`isEligible(currentHand` 早于 `canHarvestWithEmptyHand` 的位置断言——
        // 手持优先于空手回落已由 currentHandThenInventoryThenEmptyHandAreDistinctPriorities
        // 用真实 select() 结果（CURRENT_HAND / INVENTORY_TOOL / EMPTY_HAND / NONE）覆盖。
    }

    private static void assertSelectFollowsSharedEligibility(Block target, int metadata, ItemStack currentHand) {
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                currentHand, Collections.<ItemStack>emptyList(), false);
        boolean eligible = ToolHarvestEligibility.isEligible(currentHand, target, metadata);
        Assert.assertEquals("select 必须与共享资格规则同结论（hand=" + currentHand.getItem() + "）",
                eligible,
                snapshot.select(target, metadata) == PlanningToolCapabilitySnapshot.MatchKind.CURRENT_HAND);
    }

    private static final class TestBlock extends Block {
        private TestBlock(Material material) { super(material); }
    }

    private static final class TestTool extends Item {
        private TestTool() { setMaxDamage(100); }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) { return 4.0F; }
    }

    /** 未知工具仅通过 Minecraft Item 稳定虚调用声明收获能力。 */
    private static final class GenericHarvestTool extends Item {
        private final boolean throwOnSpeed;
        private GenericHarvestTool(boolean throwOnSpeed) {
            this.throwOnSpeed = throwOnSpeed;
            setMaxDamage(100);
        }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) {
            if (throwOnSpeed) throw new IllegalStateException("optional speed fact unavailable");
            return 1.0F;
        }
        @Override public boolean canHarvestBlock(Block block, ItemStack stack) { return true; }
    }

    /** 未声明 harvestTool、但材质需要工具的规划目标。 */
    private static final class NullHarvestToolBlock extends Block {
        private NullHarvestToolBlock() { super(Material.rock); }
        @Override public String getHarvestTool(int metadata) { return null; }
    }
}
