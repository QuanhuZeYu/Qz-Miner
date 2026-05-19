package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.etfuturum.EtFuturumCompatHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.tileentity.TileEntity;

/**
 * 矿石判定规则。
 */
public final class ChainOreRules {

    private static final Class<?> GT_BLOCK_ORES_ABSTRACT = resolveClass("gregtech.common.blocks.BlockOresAbstract");
    private static final Class<?> BW_META_GENERATED_SMALL_ORES = resolveClass("bartworks.system.material.BWMetaGeneratedSmallOres");
    private static final Class<?> BW_META_GENERATED_ORES = resolveClass("bartworks.system.material.BWMetaGeneratedOres");
    private static final Class<?> GTPP_BLOCK_BASE_ORE = resolveClass("gtPlusPlus.core.block.base.BlockBaseOre");
    private static final Class<?> AE_ORE_QUARTZ = resolveClass("appeng.block.solids.OreQuartz");
    private static final Class<?> AE_ORE_QUARTZ_CHARGED = resolveClass("appeng.block.solids.OreQuartzCharged");
    private static final Class<?> GT_TILE_ENTITY_ORES = resolveClass("gregtech.common.blocks.TileEntityOres");
    private static final Class<?> BW_TILE_ENTITY_META_GENERATED_ORE = resolveClass("bartworks.system.material.BWTileEntityMetaGeneratedOre");
    private static final Class<?> BW_TILE_ENTITY_META_GENERATED_SMALL_ORE = resolveClass("bartworks.system.material.BWTileEntityMetaGeneratedSmallOre");

    private ChainOreRules() {}

    /**
     * 判断当前方块是否视为矿石。
     *
     * @param block 方块
     * @return 是否为矿石
     */
    public static boolean isOreBlock(Block block) {
        return isOreBlock(block, null);
    }

    /**
     * 判断当前方块或其 TileEntity 是否视为矿石。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为矿石
     */
    public static boolean isOreBlock(Block block, TileEntity tileEntity) {
        if (block == null) {
            return false;
        }

        if (block instanceof BlockOre || block instanceof BlockRedstoneOre) {
            return true;
        }

        if (isInstance(GT_BLOCK_ORES_ABSTRACT, block)
            || isInstance(BW_META_GENERATED_SMALL_ORES, block)
            || isInstance(BW_META_GENERATED_ORES, block)
            || isInstance(GTPP_BLOCK_BASE_ORE, block)
            || isInstance(AE_ORE_QUARTZ, block)
            || isInstance(AE_ORE_QUARTZ_CHARGED, block)
            || EtFuturumCompatHelper.isOreBlock(block)) {
            return true;
        }

        if (isInstance(GT_TILE_ENTITY_ORES, tileEntity)
            || isInstance(BW_TILE_ENTITY_META_GENERATED_ORE, tileEntity)
            || isInstance(BW_TILE_ENTITY_META_GENERATED_SMALL_ORE, tileEntity)) {
            return true;
        }

        return false;
    }

    private static boolean isInstance(Class<?> type, Object instance) {
        return type != null && type.isInstance(instance);
    }

    private static Class<?> resolveClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
