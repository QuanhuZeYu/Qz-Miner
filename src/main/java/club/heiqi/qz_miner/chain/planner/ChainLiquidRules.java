package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * 与冻结 seed 同种的静态可排液液体规则。
 *
 * <p>本类只查询流体身份与 {@link IFluidBlock#canDrain}，绝不调用 drain 修改世界。</p>
 */
public final class ChainLiquidRules {

    private ChainLiquidRules() {}

    /**
     * 从静态方块类型取得稳定流体名。
     *
     * @param block seed 或候选方块
     * @return 非空流体名；无法可靠解析时返回 null
     */
    public static String fluidIdentity(Block block) {
        if (block instanceof BlockLiquid && !(block instanceof IFluidBlock)) {
            try {
                Material material = block.getMaterial();
                if (material == Material.water) {
                    return "water";
                }
                if (material == Material.lava) {
                    return "lava";
                }
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }
        Fluid fluid = resolveFluid(block);
        if (fluid == null) {
            return null;
        }
        try {
            String name = fluid.getName();
            return name == null || name.isEmpty() ? null : name;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * 判断冻结 seed 是否足以建立 source 匹配能力。
     *
     * <p>vanilla {@link BlockLiquid} 只有 metadata 0 是静态 source；Forge
     * {@link IFluidBlock} 的候选排液能力留到 live {@code canDrain} 终裁。</p>
     */
    public static boolean isSeedSource(Block seedBlock, int seedMetadata) {
        return isSeedSource(seedBlock, seedMetadata, fluidIdentity(seedBlock));
    }

    /** 包级冻结接缝，确保 matcher 对可能异常的流体身份只采样一次。 */
    static boolean isSeedSource(Block seedBlock, int seedMetadata, String seedFluidIdentity) {
        if (seedBlock == null || seedMetadata < 0
                || seedFluidIdentity == null || seedFluidIdentity.isEmpty()) {
            return false;
        }
        if (seedBlock instanceof IFluidBlock) {
            return true;
        }
        return seedBlock instanceof BlockLiquid && seedMetadata == 0;
    }

    /**
     * 按 live world 判断候选是否为同种且当前可排出的 source。
     *
     * @param seedFluidIdentity 冻结 seed 的流体名
     * @param world 当前只读世界
     * @param x 候选 X
     * @param y 候选 Y
     * @param z 候选 Z
     * @param candidateBlock 当前候选方块
     * @param candidateMetadata 当前候选完整 metadata
     * @return 是否匹配
     */
    public static boolean matchesSource(String seedFluidIdentity, World world, int x, int y, int z,
            Block candidateBlock, int candidateMetadata) {
        if (seedFluidIdentity == null || seedFluidIdentity.isEmpty()
                || candidateBlock == null || candidateMetadata < 0) {
            return false;
        }
        String candidateIdentity = fluidIdentity(candidateBlock);
        if (!seedFluidIdentity.equals(candidateIdentity)) {
            return false;
        }

        if (candidateBlock instanceof IFluidBlock) {
            try {
                return ((IFluidBlock) candidateBlock).canDrain(world, x, y, z);
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }
        return candidateBlock instanceof BlockLiquid && candidateMetadata == 0;
    }

    private static Fluid resolveFluid(Block block) {
        if (block == null) {
            return null;
        }
        if (block instanceof IFluidBlock) {
            try {
                Fluid direct = ((IFluidBlock) block).getFluid();
                if (direct != null) {
                    return direct;
                }
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }
        try {
            return FluidRegistry.lookupFluidForBlock(block);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }
}
