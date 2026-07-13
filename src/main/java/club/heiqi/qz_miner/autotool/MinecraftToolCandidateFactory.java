package club.heiqi.qz_miner.autotool;

import club.heiqi.qz_miner.MyMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;

/** 将 Minecraft 1.7.10 玩家背包转换为纯逻辑工具候选。 */
public final class MinecraftToolCandidateFactory {
    private static final int MAIN_INVENTORY_SIZE = 36;

    private MinecraftToolCandidateFactory() { }

    /** 可由 headless 测试提供的单槽事实读取接口。 */
    public interface CandidateView {
        boolean canHarvest();
        int silkTouchLevel();
        int fortuneLevel();
        double baseSpeed();
        int efficiencyLevel();
        int remainingDurability();
    }

    /**
     * 按目标坐标读取方块，并为 mainInventory 0..35 构造候选。
     *
     * @param player 玩家
     * @param world 目标世界
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 固定按库存索引排列的 36 个候选
     */
    public static List<ToolSelectionPolicy.Candidate> create(
            EntityPlayer player, World world, int x, int y, int z) {
        if (world == null) throw new IllegalArgumentException("world");
        return create(player, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z));
    }

    /**
     * 为指定方块状态构造 mainInventory 0..35 候选，不修改 currentItem。
     *
     * @param player 玩家及其背包
     * @param block 目标方块
     * @param metadata 目标 metadata
     * @return 固定按库存索引排列的 36 个候选
     */
    public static List<ToolSelectionPolicy.Candidate> create(
            EntityPlayer player, Block block, int metadata) {
        if (player == null) throw new IllegalArgumentException("player");
        if (block == null) throw new IllegalArgumentException("block");
        List<CandidateView> views = new ArrayList<CandidateView>(MAIN_INVENTORY_SIZE);
        for (int slot = 0; slot < MAIN_INVENTORY_SIZE; slot++) {
            final ItemStack stack = player.inventory.mainInventory[slot];
            views.add(stack == null ? emptyView(block, metadata) : stackView(stack, block, metadata));
        }
        return fromViews(views);
    }

    /** 将 36 个按库存索引排列的事实视图转换为策略候选。 */
    public static List<ToolSelectionPolicy.Candidate> fromViews(List<? extends CandidateView> views) {
        if (views == null || views.size() != MAIN_INVENTORY_SIZE) {
            throw new IllegalArgumentException("views must contain mainInventory slots 0..35");
        }
        List<ToolSelectionPolicy.Candidate> candidates =
                new ArrayList<ToolSelectionPolicy.Candidate>(MAIN_INVENTORY_SIZE);
        for (int slot = 0; slot < MAIN_INVENTORY_SIZE; slot++) {
            try {
                candidates.add(readCandidate(slot, views.get(slot)));
            } catch (RuntimeException e) {
                MyMod.LOG.debug("[AutoTool] Candidate slot {} degraded", Integer.valueOf(slot), e);
                candidates.add(degraded(slot));
            } catch (LinkageError e) {
                MyMod.LOG.debug("[AutoTool] Candidate slot {} linkage degraded", Integer.valueOf(slot), e);
                candidates.add(degraded(slot));
            }
        }
        return candidates;
    }

    /**
     * 将 mainInventory 索引映射到 ContainerPlayer 槽位。
     * 快捷栏 0..8 映射 36..44，主背包 9..35 保持原值。
     */
    public static int toContainerPlayerSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= MAIN_INVENTORY_SIZE) {
            throw new IllegalArgumentException("inventoryIndex must be in 0..35");
        }
        return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
    }

    private static CandidateView stackView(final ItemStack stack, final Block block, final int metadata) {
        return new CandidateView() {
            public boolean canHarvest() { return ForgeHooks.canToolHarvestBlock(block, metadata, stack); }
            public int silkTouchLevel() { return EnchantmentHelper.getEnchantmentLevel(Enchantment.silkTouch.effectId, stack); }
            public int fortuneLevel() { return EnchantmentHelper.getEnchantmentLevel(Enchantment.fortune.effectId, stack); }
            public double baseSpeed() { return stack.getItem().getDigSpeed(stack, block, metadata); }
            public int efficiencyLevel() { return EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, stack); }
            public int remainingDurability() {
                return stack.isItemStackDamageable() ? Math.max(0, stack.getMaxDamage() - stack.getItemDamage()) : Integer.MAX_VALUE;
            }
        };
    }

    private static CandidateView emptyView(final Block block, final int metadata) {
        return new CandidateView() {
            public boolean canHarvest() { return ForgeHooks.canToolHarvestBlock(block, metadata, null); }
            public int silkTouchLevel() { return 0; }
            public int fortuneLevel() { return 0; }
            public double baseSpeed() { return 1.0D; }
            public int efficiencyLevel() { return 0; }
            public int remainingDurability() { return Integer.MAX_VALUE; }
        };
    }

    private static ToolSelectionPolicy.Candidate readCandidate(int slot, CandidateView view) {
        if (view == null) return degraded(slot);
        return new ValueCandidate(slot, view.canHarvest(), view.silkTouchLevel(), view.fortuneLevel(),
                view.baseSpeed(), view.efficiencyLevel(), view.remainingDurability());
    }

    private static ToolSelectionPolicy.Candidate degraded(int slot) {
        return new ValueCandidate(slot, false, 0, 0, 0.0D, 0, 0);
    }

    private static final class ValueCandidate implements ToolSelectionPolicy.Candidate {
        private final int slot;
        private final boolean canHarvest;
        private final int silkTouchLevel;
        private final int fortuneLevel;
        private final double baseSpeed;
        private final int efficiencyLevel;
        private final int remainingDurability;

        private ValueCandidate(int slot, boolean canHarvest, int silkTouchLevel, int fortuneLevel,
                double baseSpeed, int efficiencyLevel, int remainingDurability) {
            this.slot = slot;
            this.canHarvest = canHarvest;
            this.silkTouchLevel = silkTouchLevel;
            this.fortuneLevel = fortuneLevel;
            this.baseSpeed = baseSpeed;
            this.efficiencyLevel = efficiencyLevel;
            this.remainingDurability = remainingDurability;
        }

        public int slot() { return slot; }
        public boolean canHarvest() { return canHarvest; }
        public int silkTouchLevel() { return silkTouchLevel; }
        public int fortuneLevel() { return fortuneLevel; }
        public double baseSpeed() { return baseSpeed; }
        public int efficiencyLevel() { return efficiencyLevel; }
        public int remainingDurability() { return remainingDurability; }
    }
}
