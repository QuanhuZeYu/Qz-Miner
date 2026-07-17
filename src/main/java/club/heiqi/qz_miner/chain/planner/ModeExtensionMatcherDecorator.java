package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;

/** 按子模式为既有规划判定附加冻结对象组扩展。 */
public final class ModeExtensionMatcherDecorator {

    private ModeExtensionMatcherDecorator() {}

    /** 为目标匹配器附加模式安全门。 */
    public static ChainBlockMatcher decorateMatcher(
        ChainSubMode subMode, ChainBlockMatcher base, FrozenModePredicate extension) {
        if (base == null || extension == null || extension.snapshot().isEmpty() || !isSupported(subMode)) {
            return base;
        }
        return decorateMatcher(subMode, base,
            (player, target) -> player != null && extension.matches(player.worldObj, target),
            ChainHarvestRules::canHarvest,
            ModeExtensionMatcherDecorator::isValidCropTarget);
    }

    /** 使用冻结快照为目标匹配器附加模式安全门。 */
    public static ChainBlockMatcher decorateMatcher(
        ChainSubMode subMode, ChainBlockMatcher base, ModeExtensionSnapshot snapshot) {
        return decorateMatcher(subMode, base, new FrozenModePredicate(snapshot));
    }

    /** 为候选过滤器附加 Q OR X；采掘门只在最终 matcher 阶段执行。 */
    public static ChainCandidateFilter decorateCandidateFilter(
        ChainSubMode subMode, ChainCandidateFilter base, FrozenModePredicate extension,
        net.minecraft.world.World world) {
        if (base == null || extension == null || extension.snapshot().isEmpty() || !isSupported(subMode)) {
            return base;
        }
        return decorateCandidateFilter(subMode, base, target -> extension.matches(world, target));
    }

    static ChainBlockMatcher decorateMatcher(
        final ChainSubMode subMode,
        final ChainBlockMatcher base,
        final ExtensionMatch extension,
        final ModeGate harvestGate,
        final ModeGate cropGate) {
        if (base == null || extension == null || !isSupported(subMode)) return base;
        return (player, target) -> {
            if (base.matches(player, target)) return true;
            if (!extension.matches(player, target)) return false;
            if (isHarvestMode(subMode)) return harvestGate.test(player, target);
            if (subMode == ChainSubMode.INTERACT_CROP) return cropGate.test(player, target);
            return subMode == ChainSubMode.INTERACT_BASE;
        };
    }

    static ChainCandidateFilter decorateCandidateFilter(
        ChainSubMode subMode, final ChainCandidateFilter base, final CandidateExtension extension) {
        if (base == null || extension == null || !isSupported(subMode)) return base;
        return target -> base.canTraverse(target) || extension.matches(target);
    }

    private static boolean isValidCropTarget(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null || player.worldObj == null) return false;
        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        return block != null && block != Blocks.air && !block.getMaterial().isLiquid();
    }

    private static boolean isSupported(ChainSubMode subMode) {
        return isHarvestMode(subMode)
            || subMode == ChainSubMode.INTERACT_BASE
            || subMode == ChainSubMode.INTERACT_CROP;
    }

    private static boolean isHarvestMode(ChainSubMode subMode) {
        return subMode == ChainSubMode.CHAIN_BASE
            || subMode == ChainSubMode.CHAIN_ORE
            || subMode == ChainSubMode.CHAIN_LOGGING
            || subMode == ChainSubMode.AREA_SAME_BLOCK
            || subMode == ChainSubMode.AREA_ORE;
    }

    interface ExtensionMatch {
        boolean matches(EntityPlayer player, ChainTarget target);
    }

    interface ModeGate {
        boolean test(EntityPlayer player, ChainTarget target);
    }

    interface CandidateExtension {
        boolean matches(ChainTarget target);
    }
}
