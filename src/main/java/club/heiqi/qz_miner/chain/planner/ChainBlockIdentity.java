package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 方块身份判定工具。
 */
public final class ChainBlockIdentity {

    private ChainBlockIdentity() {}

    /**
     * 判断目标坐标上的方块是否与起点视为同类。
     *
     * @param world 当前世界
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     * @param sampleTileEntity 起点 TileEntity
     * @param target 目标位置
     * @return 是否视为同类
     */
    public static boolean matches(World world, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity, ChainTarget target) {
        return matches(world, sampleBlock, sampleMeta,
                CompatAdapters.captureTileIdentity(sampleTileEntity), target, null);
    }

    /** 使用主线程冻结的纯值身份判断目标坐标上的方块是否同类。 */
    public static boolean matches(World world, Block sampleBlock, int sampleMeta,
            TileIdentityToken sampleTileIdentity, ChainTarget target) {
        return matches(world, sampleBlock, sampleMeta, sampleTileIdentity, target, null);
    }

    /**
     * 判断目标身份并复用本次读取结果填充有界诊断，不额外读取世界。
     *
     * @param diagnostics round 级诊断器，可为 null
     * @return 是否视为同类
     */
    static boolean matches(World world, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity,
            ChainTarget target, ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        return matches(world, sampleBlock, sampleMeta,
                CompatAdapters.captureTileIdentity(sampleTileEntity), target, diagnostics);
    }

    /** 先比较 block/meta，再把候选 live TE 立即纯值化后执行 token 矩阵。 */
    static boolean matches(World world, Block sampleBlock, int sampleMeta, TileIdentityToken sampleTileIdentity,
            ChainTarget target, ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (world == null || sampleBlock == null || target == null) {
            return false;
        }

        Block targetBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (diagnostics != null) {
            diagnostics.captureCandidateBlock(target, targetBlock, -1);
        }
        if (targetBlock == null || targetBlock == Blocks.air || targetBlock != sampleBlock) {
            return false;
        }

        int targetMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        if (diagnostics != null) {
            diagnostics.captureCandidateBlock(target, targetBlock, targetMeta);
        }
        if (targetMeta != sampleMeta) {
            return false;
        }

        TileIdentityToken targetTileIdentity;
        try {
            TileEntity targetTileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
            targetTileIdentity = CompatAdapters.captureTileIdentity(targetTileEntity);
        } catch (RuntimeException | LinkageError failure) {
            targetTileIdentity = TileIdentityToken.unresolved();
        }
        boolean result = CompatAdapters.matchesTileIdentity(sampleTileIdentity, targetTileIdentity);
        if (diagnostics != null) {
            diagnostics.recordTileIdentityResult(target, sampleTileIdentity, targetTileIdentity, result);
        }
        return result;
    }

    /**
     * 判断两个 TileEntity 是否可视为同类。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 目标 TileEntity
     * @return 是否可视为同类
     */
    public static boolean matchesTileEntity(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        return CompatAdapters.matchesTileEntity(sampleTileEntity, targetTileEntity);
    }

    /** 比较两个已冻结纯值身份。 */
    public static boolean matchesTileIdentity(TileIdentityToken sampleIdentity, TileIdentityToken targetIdentity) {
        return CompatAdapters.matchesTileIdentity(sampleIdentity, targetIdentity);
    }
}
