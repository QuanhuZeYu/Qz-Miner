package club.heiqi.qz_miner.util;

import club.heiqi.qz_miner.MY_LOG;

public class CheckCompatibility {
    public static boolean isHaseClass_BlockBaseOre = false;
    public static boolean isHasClass_BlockOresAbstract = false;
    public static boolean isHasClass_TileEntityOre = false;
    public static boolean isHasClass_BWTileEntityGenOre = false;
    public static boolean isHasClass_MSMTile = false;
    public static boolean isHasClass_AE2 = false;

    public static boolean is270Upper = false;

    public static void checkAll() {
        isHasClass_BlockOresAbstract();
        isHasClass_TileEntityOre();
        isHaseClass_BlockBaseOre();
        isHaseClass_BWTileEntityGenOre();
        MY_LOG.LOG.info("检测到所有类");
        isHaseClass_MSMTile();
        is270Upper = isHasClass_BlockOresAbstract && isHasClass_TileEntityOre && isHaseClass_BlockBaseOre && isHasClass_BWTileEntityGenOre;
        isHasClass_AE2();
    }

    public static void isHasClass_BlockOresAbstract() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.BlockOresAbstract");
            isHasClass_BlockOresAbstract = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 粗矿类");
            isHasClass_BlockOresAbstract = false;
        }
    }

    public static void isHasClass_TileEntityOre() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.TileEntityOres");
            isHasClass_TileEntityOre = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 TileEntityOres");
            isHasClass_TileEntityOre = false;
        }
    }

    public static void isHaseClass_BlockBaseOre() {
        try {
            Class<?> clazz = Class.forName("gtPlusPlus.core.block.base.BlockBaseOre");
            isHaseClass_BlockBaseOre = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 粗矿类");
            isHaseClass_BlockBaseOre = false;
        }
    }

    public static void isHaseClass_BWTileEntityGenOre() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.BWTileEntityMetaGeneratedOre");
            isHasClass_BWTileEntityGenOre = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 BWTileEntityGenOre");
            isHasClass_BWTileEntityGenOre = false;
        }
    }

    public static void isHaseClass_MSMTile() {
        try {
            Class<?> clazz = Class.forName("ru.timeconqueror.lootgames.common.block.tile.MSMasterTile");
            isHasClass_MSMTile = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 MSMTile");
            isHasClass_MSMTile = false;
        }
    }

    public static void isHasClass_AE2() {
        try {
            Class<?> clazz = Class.forName("appeng.tile.AEBaseTile");
            isHasClass_AE2 = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.LOG.warn("未检测到 AEBaseTile");
            isHasClass_AE2 = false;
        }
    }
}
