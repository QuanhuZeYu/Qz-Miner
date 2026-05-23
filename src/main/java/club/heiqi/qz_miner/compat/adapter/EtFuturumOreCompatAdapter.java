package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * Et Futurum Requiem 矿石兼容适配器。
 */
public final class EtFuturumOreCompatAdapter implements OreCompatAdapter {

    private static final String ET_FUTURUM_MODID = "etfuturum";
    private static final String ET_FUTURUM_ANCIENT_DEBRIS = "ancient_debris";

    private final Class<?> netherGoldOreType;
    private final Class<?> ancientDebrisType;
    private final Class<?> deepslateOreType;
    private final Class<?> moddedDeepslateOreType;

    /**
     * 创建 EFR 矿石适配器。
     */
    public EtFuturumOreCompatAdapter() {
        this.netherGoldOreType = ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.ores.BlockOreNetherGold");
        this.ancientDebrisType = ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.BlockAncientDebris");
        this.deepslateOreType = ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.ores.BaseDeepslateOre");
        this.moddedDeepslateOreType = ClassNameCompatSupport.resolveClass(
            "ganymedes01.etfuturum.blocks.ores.modded.BlockGeneralModdedDeepslateOre");
    }

    @Override
    public boolean isAvailable() {
        return netherGoldOreType != null
            || ancientDebrisType != null
            || deepslateOreType != null
            || moddedDeepslateOreType != null;
    }

    @Override
    public boolean isOreBlock(Block block, TileEntity tileEntity) {
        if (block == null) {
            return false;
        }

        if (ClassNameCompatSupport.isInstance(netherGoldOreType, block)
            || ClassNameCompatSupport.isInstance(ancientDebrisType, block)
            || ClassNameCompatSupport.isInstance(deepslateOreType, block)
            || ClassNameCompatSupport.isInstance(moddedDeepslateOreType, block)) {
            return true;
        }

        String registryName = resolveRegistryName(block);
        if (registryName == null || !registryName.startsWith(ET_FUTURUM_MODID + ":")) {
            return false;
        }

        String path = registryName.substring(ET_FUTURUM_MODID.length() + 1);
        return path.endsWith("_ore") || ET_FUTURUM_ANCIENT_DEBRIS.equals(path);
    }

    private String resolveRegistryName(Block block) {
        Object registryName = Block.blockRegistry.getNameForObject(block);
        return registryName instanceof String ? (String) registryName : null;
    }
}
