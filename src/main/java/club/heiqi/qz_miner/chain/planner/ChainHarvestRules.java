package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.toolswap.AutoToolUsabilityPolicy;
import club.heiqi.qz_miner.toolswap.server.MinecraftAutoToolSwapInventoryPort;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/**
 * 连锁挖掘共享判定规则。
 */
public final class ChainHarvestRules {

    /** 规划 matcher 复用的世界与安全 admission；不读取工具或库存能力。 */
    static final HarvestEvaluator DEFAULT_EVALUATOR = new HarvestEvaluator() {
        @Override
        public HarvestEvaluation evaluate(EntityPlayer player, ChainTarget target, boolean diagnosticTracking) {
            return evaluatePlanningAdmission(player, target);
        }
    };

    /** 主线程执行期复用的完整采掘判定。 */
    private static final HarvestEvaluator EXECUTION_EVALUATOR = new HarvestEvaluator() {
        @Override
        public HarvestEvaluation evaluate(EntityPlayer player, ChainTarget target, boolean diagnosticTracking) {
            return evaluateExecutionHarvest(player, target, diagnosticTracking);
        }
    };

    private ChainHarvestRules() {}

    /** 保留给冻结能力内部类型测试的 evaluator；生产 planner 不再绑定该路径。 */
    static HarvestEvaluator planningEvaluator(final PlanningToolCapabilitySnapshot capabilitySnapshot) {
        if (capabilitySnapshot == null) return DEFAULT_EVALUATOR;
        return new HarvestEvaluator() {
            @Override
            public HarvestEvaluation evaluate(EntityPlayer player, ChainTarget target, boolean diagnosticTracking) {
                return evaluateFrozenPlanningHarvest(player, target, diagnosticTracking, capabilitySnapshot);
            }
        };
    }

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

        return AutoToolUsabilityPolicy.hasDurabilityReserve(
                equippedItem.getMaxDamage() - equippedItem.getItemDamage());
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
        HarvestEvaluation evaluation = EXECUTION_EVALUATOR.evaluate(player, target, false);
        return evaluation.isAccepted();
    }

    /**
     * 仅用于规划线程判断目标的世界与脚底安全 admission，不读取当前工具或库存。
     *
     * @param player 玩家
     * @param target 目标方块
     * @return 目标是否应进入待执行队列
     */
    public static boolean canPlanHarvest(EntityPlayer player, ChainTarget target) {
        return canPlanHarvest(player, target, null);
    }

    /**
     * 按原短路顺序判定采掘并复用已读取值编码诊断原因。
     *
     * @param diagnostics round 级诊断器，可为 null
     * @return 是否允许挖掘
     */
    static boolean canPlanHarvest(EntityPlayer player, ChainTarget target,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        HarvestEvaluation evaluation = DEFAULT_EVALUATOR.evaluate(player, target,
                diagnostics != null && diagnostics.isTracking(target));
        evaluation.record(diagnostics, target);
        return evaluation.isAccepted();
    }

    /** 规划 worker 只读取世界/几何安全事实，工具能力全部推迟到主线程逐目标执行。 */
    private static HarvestEvaluation evaluatePlanningAdmission(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null || player.worldObj == null) {
            return new HarvestEvaluation(false, null, -1, "planning-tool-not-read", "planning-deferred",
                    "planning-deferred", "invalid-input");
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        boolean validWorldTarget = block != null && block != Blocks.air && block != Blocks.bedrock
                && !block.getMaterial().isLiquid();
        if (!validWorldTarget) {
            return new HarvestEvaluation(false, block, -1, "planning-tool-not-read", "planning-deferred",
                    "planning-deferred", "world-view");
        }
        if (!acceptsPlanningAdmission(true, isStandingOnTarget(player, target))) {
            return new HarvestEvaluation(false, block, -1, "planning-tool-not-read", "planning-deferred",
                    "planning-deferred", "standing-on-target");
        }

        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return new HarvestEvaluation(true, block, meta, "planning-tool-not-read", "planning-deferred",
                "planning-deferred", "accepted");
    }

    /** 按主线程执行短路顺序读取一次实时业务状态。 */
    private static HarvestEvaluation evaluateExecutionHarvest(EntityPlayer player, ChainTarget target,
            boolean diagnosticTracking) {
        if (player == null || target == null || player.worldObj == null) {
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
        boolean enoughDurability = player.capabilities.isCreativeMode
                || AutoToolUsabilityPolicy.hasDurabilityReserve(remainingDurability(equippedItem));
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

    /** 保留的内部冻结能力类型测试路径；生产 planner 不再调用。 */
    private static HarvestEvaluation evaluateFrozenPlanningHarvest(EntityPlayer player, ChainTarget target,
            boolean diagnosticTracking, PlanningToolCapabilitySnapshot capabilitySnapshot) {
        if (player == null || target == null || player.worldObj == null) {
            return new HarvestEvaluation(false, null, -1, "frozen-capabilities", "planning-frozen",
                    "not-run", "invalid-input");
        }
        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block == Blocks.bedrock || block.getMaterial().isLiquid()) {
            return new HarvestEvaluation(false, block, -1, "frozen-capabilities", "planning-frozen",
                    "not-run", "world-view");
        }
        if (isStandingOnTarget(player, target)) {
            return new HarvestEvaluation(false, block, -1, "frozen-capabilities", "planning-frozen",
                    "not-run", "standing-on-target");
        }
        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        PlanningToolCapabilitySnapshot.MatchKind match = capabilitySnapshot.select(block, meta);
        boolean accepted = match != PlanningToolCapabilitySnapshot.MatchKind.NONE;
        String summary = diagnosticTracking ? "frozen-match=" + match : "frozen-capabilities";
        return new HarvestEvaluation(accepted, block, meta, summary, "planning-frozen",
                String.valueOf(accepted), accepted ? "accepted" : "frozen-capability-rejected");
    }

    /** 对已捕获 ItemStack 执行与公开耐久规则相同的纯值判定。 */
    private static boolean hasEnoughDurability(ItemStack equippedItem) {
        return acceptsDurabilityForPhase(remainingDurability(equippedItem), false);
    }

    /** 将不可损耗物与空手统一映射为无限剩余耐久。 */
    private static int remainingDurability(ItemStack equippedItem) {
        return equippedItem == null || !equippedItem.isItemStackDamageable() ? Integer.MAX_VALUE
                : equippedItem.getMaxDamage() - equippedItem.getItemDamage();
    }

    /** 纯值测试接缝：规划期不以瞬时耐久拒绝，执行期使用统一储备门。 */
    static boolean acceptsDurabilityForPhase(int remainingDurability, boolean planningPhase) {
        return planningPhase || AutoToolUsabilityPolicy.hasDurabilityReserve(remainingDurability);
    }

    /** 纯值接缝：合法世界目标只会被脚底安全门拒绝，不接收任何工具事实。 */
    static boolean acceptsPlanningAdmission(boolean validWorldTarget, boolean standingOnTarget) {
        return validWorldTarget && !standingOnTarget;
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
