package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.toolswap.server.MinecraftAutoToolSwapInventoryPort;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/**
 * 连锁挖掘共享判定规则。
 */
public final class ChainHarvestRules {

    /** 正式 matcher 复用的不可变采掘判定策略。 */
    static final HarvestEvaluator DEFAULT_EVALUATOR = new HarvestEvaluator() {
        @Override
        public HarvestEvaluation evaluate(EntityPlayer player, ChainTarget target, boolean diagnosticTracking) {
            return evaluateHarvest(player, target, diagnosticTracking);
        }
    };

    private ChainHarvestRules() {}

    /**
     * 判断当前工具耐久是否仍允许继续连锁。
     *
     * @param player 玩家
     * @return 是否仍允许继续连锁
     */
    public static boolean hasEnoughDurability(EntityPlayer player) {
        if (player == null || player.capabilities.isCreativeMode) {
            return true;
        }

        ItemStack equippedItem = player.getCurrentEquippedItem();
        if (equippedItem == null || !equippedItem.isItemStackDamageable()) {
            return true;
        }

        return equippedItem.getMaxDamage() - equippedItem.getItemDamage() > 1;
    }

    /**
     * 判断目标是否为玩家脚底方块。
     *
     * @param player 玩家
     * @param target 目标方块
     * @return 是否为脚底保护方块
     */
    public static boolean isStandingOnTarget(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        return target.getX() == (int) Math.floor(player.posX)
            && target.getY() == (int) Math.floor(player.posY) - 1
            && target.getZ() == (int) Math.floor(player.posZ);
    }

    /**
     * 判断目标方块当前是否允许进入连锁挖掘流程。
     *
     * @param player 玩家
     * @param target 目标方块
     * @return 是否允许挖掘
     */
    public static boolean canHarvest(EntityPlayer player, ChainTarget target) {
        return canHarvest(player, target, null);
    }

    /**
     * 按原短路顺序判定采掘并复用已读取值编码诊断原因。
     *
     * @param diagnostics round 级诊断器，可为 null
     * @return 是否允许挖掘
     */
    static boolean canHarvest(EntityPlayer player, ChainTarget target,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        HarvestEvaluation evaluation = DEFAULT_EVALUATOR.evaluate(player, target,
                diagnostics != null && diagnostics.isTracking(target));
        evaluation.record(diagnostics, target);
        return evaluation.isAccepted();
    }

    /** 按原短路顺序读取一次业务状态，并返回供 matcher 统一记录的纯值结果。 */
    private static HarvestEvaluation evaluateHarvest(EntityPlayer player, ChainTarget target,
            boolean diagnosticTracking) {
        if (player == null || target == null) {
            return new HarvestEvaluation(false, null, -1, "not-read", "not-run", "not-run", "invalid-input");
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block == Blocks.bedrock || block.getMaterial().isLiquid()) {
            return new HarvestEvaluation(false, block, -1, "not-read", "not-run", "not-run", "world-view");
        }

        if (isStandingOnTarget(player, target)) {
            return new HarvestEvaluation(false, block, -1, "not-read", "not-run", "not-run",
                    "standing-on-target");
        }

        ItemStack equippedItem = player.capabilities.isCreativeMode ? null : player.getCurrentEquippedItem();
        boolean enoughDurability = player.capabilities.isCreativeMode || hasEnoughDurability(equippedItem);
        String toolSummary = diagnosticTracking
                ? MinecraftAutoToolSwapInventoryPort.describeStack(equippedItem) : "not-recorded";
        if (!enoughDurability) {
            return new HarvestEvaluation(false, block, -1, toolSummary, "false", "not-run",
                    "durability-insufficient");
        }

        if (player.capabilities.isCreativeMode) {
            return new HarvestEvaluation(true, block, -1, "creative-not-read", "true", "creative-bypass",
                    "accepted");
        }

        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        boolean canHarvestBlock = block.canHarvestBlock(player, meta);
        return new HarvestEvaluation(canHarvestBlock, block, meta, toolSummary, "true",
                String.valueOf(canHarvestBlock), canHarvestBlock ? "accepted" : "can-harvest-block-rejected");
    }

    /** 对已捕获 ItemStack 执行与公开耐久规则相同的纯值判定。 */
    private static boolean hasEnoughDurability(ItemStack equippedItem) {
        return equippedItem == null || !equippedItem.isItemStackDamageable()
                || equippedItem.getMaxDamage() - equippedItem.getItemDamage() > 1;
    }

    /** 纯值原因编码接缝，供测试证明各拒绝层可区分。 */
    static String encodeDiagnosticReason(boolean validBlock, boolean standingOnTarget,
            boolean enoughDurability, boolean creative, boolean canHarvestBlock) {
        if (!validBlock) return "world-view";
        if (standingOnTarget) return "standing-on-target";
        if (!enoughDurability) return "durability-insufficient";
        if (creative || canHarvestBlock) return "accepted";
        return "can-harvest-block-rejected";
    }

    /** 不可变采掘判定策略；实现只返回单次读取所得结果。 */
    interface HarvestEvaluator {
        HarvestEvaluation evaluate(EntityPlayer player, ChainTarget target, boolean diagnosticTracking);
    }

    /** 不可变目标身份分类策略。 */
    interface TargetClassifier {
        boolean matches(EntityPlayer player, ChainTarget target);
    }

    /** 单次采掘判定的不可变纯值快照。 */
    static final class HarvestEvaluation {

        private final boolean accepted;
        private final Block block;
        private final int meta;
        private final String toolSummary;
        private final String durabilityResult;
        private final String canHarvestResult;
        private final String reason;

        HarvestEvaluation(boolean accepted, Block block, int meta, String toolSummary, String durabilityResult,
                String canHarvestResult, String reason) {
            this.accepted = accepted;
            this.block = block;
            this.meta = meta;
            this.toolSummary = toolSummary;
            this.durabilityResult = durabilityResult;
            this.canHarvestResult = canHarvestResult;
            this.reason = reason;
        }

        boolean isAccepted() {
            return accepted;
        }

        /** 由正式 matcher/共享规则统一记录，判定策略本身不写诊断状态。 */
        void record(ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics, ChainTarget target) {
            if (diagnostics != null) {
                diagnostics.logWorkerReadOnce(Thread.currentThread().getName());
                diagnostics.recordHarvestResult(target, block, meta, toolSummary, durabilityResult,
                        canHarvestResult, reason);
            }
        }
    }
}
