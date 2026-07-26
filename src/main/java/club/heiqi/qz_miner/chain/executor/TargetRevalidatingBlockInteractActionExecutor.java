package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ImmatureCropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ModeExtensionMatcherDecorator;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.chain.state.ChainRequest;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;

/**
 * 在目标化方块右键前按冻结请求与 live world 重验目标身份。
 */
public final class TargetRevalidatingBlockInteractActionExecutor extends BlockInteractActionExecutor {

    private final ChainSubMode expectedSubMode;

    /**
     * 创建只服务于指定范围交互子模式的执行器。
     *
     * @param expectedSubMode 注册该执行器的目标子模式
     */
    public TargetRevalidatingBlockInteractActionExecutor(ChainSubMode expectedSubMode) {
        this.expectedSubMode = expectedSubMode;
    }

    /**
     * 先复用通用权限门，再以当前请求冻结的 seed 与对象组重验 live 目标。
     */
    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (!super.canExecute(player, session, target)) {
            return false;
        }
        try {
            ChainBlockMatcher matcher = createLiveMatcher(session.getRequest(), expectedSubMode);
            return matcher != null && matcher.matches(player, target);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error(
                "[TargetRevalidatingBlockInteractActionExecutor] Failed live target check for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
            return false;
        }
    }

    /**
     * 按注册子模式构造本目标使用的 live matcher；任何请求身份缺口均返回 null。
     */
    static ChainBlockMatcher createLiveMatcher(ChainRequest request, ChainSubMode expectedSubMode) {
        if (request == null
                || request.getMode() != ChainMode.INTERACT
                || request.getSubMode() != expectedSubMode
                || !hasFrozenSeed(request)) {
            return null;
        }

        if (expectedSubMode == ChainSubMode.INTERACT_BASE) {
            ChainBlockMatcher sameBlock = new SameBlockMatcher(
                    request.getSeedBlock(), request.getSeedMeta(), request.getSeedTileIdentity());
            return ModeExtensionMatcherDecorator.decorateMatcher(
                    expectedSubMode, sameBlock, request.getModeExtension());
        }
        if (expectedSubMode == ChainSubMode.INTERACT_CROP) {
            return ModeExtensionMatcherDecorator.decorateMatcher(
                    expectedSubMode, new CropBlockMatcher(), request.getModeExtension());
        }
        if (expectedSubMode == ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP) {
            return new ImmatureCropBlockMatcher();
        }
        return null;
    }

    /** 冻结 seed 必须包含非空气方块、完整 metadata 与可比较 TileEntity 状态。 */
    private static boolean hasFrozenSeed(ChainRequest request) {
        TileIdentityToken seedTileIdentity = request.getSeedTileIdentity();
        return request.getSeedBlock() != null
                && request.getSeedBlock() != Blocks.air
                && request.getSeedMeta() >= 0
                && seedTileIdentity != null
                && seedTileIdentity.isResolved();
    }
}
