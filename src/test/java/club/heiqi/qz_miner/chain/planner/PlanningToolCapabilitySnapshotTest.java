package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import tconstruct.library.tools.HarvestTool;

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

    /** 冻结规划与客户端候选共用 TiC null-harvestTool 资格入口。 */
    @Test
    public void tconstructNullHarvestToolCapabilityIsFrozenThroughSharedEligibility() {
        PlanningToolCapabilitySnapshot snapshot = PlanningToolCapabilitySnapshot.fromOrderedStacks(
                null, Collections.<ItemStack>singletonList(new ItemStack(new LegacyHarvestTool())), false);

        Assert.assertEquals(PlanningToolCapabilitySnapshot.MatchKind.INVENTORY_TOOL,
                snapshot.select(new NullHarvestToolBlock(), 2));
    }

    @Test
    public void productionSnapshotUsesSelectorOrderAndSharedEligibilityWithoutWorkerInventoryReads()
            throws Exception {
        String snapshot = source("src/main/java/club/heiqi/qz_miner/chain/planner/PlanningToolCapabilitySnapshot.java");
        String rules = source("src/main/java/club/heiqi/qz_miner/chain/planner/ChainHarvestRules.java");
        Assert.assertTrue(snapshot.contains("ToolCandidateOrder.sort(identities, selectors)"));
        Assert.assertTrue(snapshot.contains("ToolHarvestEligibility.isEligible(currentHand"));
        Assert.assertTrue(snapshot.indexOf("ToolHarvestEligibility.isEligible(currentHand")
                < snapshot.indexOf("ToolHarvestEligibility.canHarvestWithEmptyHand"));
        int frozenEvaluator = rules.indexOf("private static HarvestEvaluation evaluateFrozenPlanningHarvest");
        String workerRule = rules.substring(frozenEvaluator);
        Assert.assertFalse(workerRule.contains("getCurrentEquippedItem"));
        Assert.assertFalse(workerRule.contains("player.inventory"));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static final class TestBlock extends Block {
        private TestBlock(Material material) { super(material); }
    }

    private static final class TestTool extends Item {
        private TestTool() { setMaxDamage(100); }
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) { return 4.0F; }
    }

    /** TiC 合成旧式工具。 */
    private static final class LegacyHarvestTool extends HarvestTool {
        @Override public float getDigSpeed(ItemStack stack, Block block, int metadata) { return 4.0F; }
        @Override public boolean canHarvestBlock(Block block, ItemStack stack) { return true; }
    }

    /** 未声明 harvestTool、但材质需要工具的规划目标。 */
    private static final class NullHarvestToolBlock extends Block {
        private NullHarvestToolBlock() { super(Material.rock); }
        @Override public String getHarvestTool(int metadata) { return null; }
    }
}
