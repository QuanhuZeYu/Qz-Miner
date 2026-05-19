package club.heiqi.qz_miner.compat.etfuturum;

import net.minecraft.block.Block;

/**
 * Et Futurum Requiem 可选兼容辅助。
 */
public final class EtFuturumCompatHelper {

    private static final String ET_FUTURUM_MODID = "etfuturum";
    private static final String ET_FUTURUM_ANCIENT_DEBRIS = "ancient_debris";
    private static final Class<?> ET_FUTURUM_BLOCK_BERRY_BUSH = resolveClass("ganymedes01.etfuturum.blocks.BlockBerryBush");
    private static final Class<?> ET_FUTURUM_BASE_CAVE_VINES = resolveClass("ganymedes01.etfuturum.blocks.BaseCaveVines");
    private static final Class<?> ET_FUTURUM_BLOCK_ORE_NETHER_GOLD = resolveClass("ganymedes01.etfuturum.blocks.ores.BlockOreNetherGold");
    private static final Class<?> ET_FUTURUM_BLOCK_ANCIENT_DEBRIS = resolveClass("ganymedes01.etfuturum.blocks.BlockAncientDebris");
    private static final Class<?> ET_FUTURUM_BASE_DEEPSLATE_ORE = resolveClass("ganymedes01.etfuturum.blocks.ores.BaseDeepslateOre");
    private static final Class<?> ET_FUTURUM_BLOCK_GENERAL_MODDED_DEEPSLATE_ORE = resolveClass(
        "ganymedes01.etfuturum.blocks.ores.modded.BlockGeneralModdedDeepslateOre");

    private EtFuturumCompatHelper() {}

    /**
     * 判断方块是否为 EFR 的可右键收获作物。
     *
     * @param block 方块
     * @return 是否为 EFR 作物
     */
    public static boolean isCropBlock(Block block) {
        return isInstance(ET_FUTURUM_BLOCK_BERRY_BUSH, block)
            || isInstance(ET_FUTURUM_BASE_CAVE_VINES, block);
    }

    /**
     * 判断方块是否为 EFR 的矿石类方块。
     *
     * @param block 方块
     * @return 是否视为矿石
     */
    public static boolean isOreBlock(Block block) {
        if (block == null) {
            return false;
        }

        if (isInstance(ET_FUTURUM_BLOCK_ORE_NETHER_GOLD, block)
            || isInstance(ET_FUTURUM_BLOCK_ANCIENT_DEBRIS, block)
            || isInstance(ET_FUTURUM_BASE_DEEPSLATE_ORE, block)
            || isInstance(ET_FUTURUM_BLOCK_GENERAL_MODDED_DEEPSLATE_ORE, block)) {
            return true;
        }

        String registryName = resolveRegistryName(block);
        if (registryName == null || !registryName.startsWith(ET_FUTURUM_MODID + ":")) {
            return false;
        }

        String path = registryName.substring(ET_FUTURUM_MODID.length() + 1);
        return path.endsWith("_ore") || ET_FUTURUM_ANCIENT_DEBRIS.equals(path);
    }

    private static boolean isInstance(Class<?> type, Object instance) {
        return type != null && type.isInstance(instance);
    }

    private static String resolveRegistryName(Block block) {
        Object registryName = Block.blockRegistry.getNameForObject(block);
        return registryName instanceof String ? (String) registryName : null;
    }

    private static Class<?> resolveClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
