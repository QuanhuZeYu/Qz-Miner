package club.heiqi.qz_miner.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CheckCompatibility {
    public static Logger LOG = LogManager.getLogger();
    public static boolean isHasClass_BlockBaseOre = false;
    public static boolean isHasClass_BlockOresAbstract = false;
    public static boolean isHasClass_TileEntityOre = false;
    public static boolean isHasClass_BWTileEntityGenOre = false;
    public static boolean isHasClass_MSMTile = false;
    /**格雷机器方块基类*/
    public static boolean isHasClass_BlockCasingsAbstract =false; // 机器方块基类
    /**格雷瓷砖基类*/
    public static boolean isHasClass_MetaTileEntity = false; // 格雷瓷砖基类
    /**BartWork瓷砖基类*/
    public static boolean isHasClass_TileEntityMetaGeneratedBlock = false; // bartWork瓷砖基类
    public static boolean isHasClass_AE2 = false;

    public static void checkAll() {
        isHasClass_BlockOresAbstract();
        isHasClass_TileEntityOre();
        isHaseClass_BlockBaseOre();
        isHaseClass_BWTileEntityGenOre();
        isHasClass_BlockCasingsAbstract();
        isHasClass_MetaTileEntity();
        isHasClass_TileEntityMetaGeneratedBlock();
        isHaseClass_MSMTile();
        isHasClass_AE2();
        LOG.info("完成类检查");
    }

    public static void isHasClass_BlockOresAbstract() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.BlockOresAbstract");
            isHasClass_BlockOresAbstract = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 粗矿类");
            isHasClass_BlockOresAbstract = false;
        }
    }

    public static void isHasClass_TileEntityOre() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.TileEntityOres");
            isHasClass_TileEntityOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityOres");
            isHasClass_TileEntityOre = false;
        }
    }

    public static void isHaseClass_BlockBaseOre() {
        try {
            Class<?> clazz = Class.forName("gtPlusPlus.core.block.base.BlockBaseOre");
            isHasClass_BlockBaseOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 粗矿类");
            isHasClass_BlockBaseOre = false;
        }
    }

    public static void isHaseClass_BWTileEntityGenOre() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.BWTileEntityMetaGeneratedOre");
            isHasClass_BWTileEntityGenOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BWTileEntityGenOre");
            isHasClass_BWTileEntityGenOre = false;
        }
    }

    public static void isHaseClass_MSMTile() {
        try {
            Class<?> clazz = Class.forName("ru.timeconqueror.lootgames.common.block.tile.MSMasterTile");
            isHasClass_MSMTile = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 MSMTile");
            isHasClass_MSMTile = false;
        }
    }

    public static void isHasClass_BlockCasingsAbstract() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.BlockCasingsAbstract");
            isHasClass_BlockCasingsAbstract = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BlockCasingsAbstract");
            isHasClass_BlockCasingsAbstract = false;
        }
    }

    public static void isHasClass_MetaTileEntity() {
        try {
            Class<?> clazz = Class.forName("gregtech.api.metatileentity.MetaTileEntity");
            isHasClass_MetaTileEntity = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 MetaTileEntity");
            isHasClass_MetaTileEntity = false;
        }
    }

    public static void isHasClass_TileEntityMetaGeneratedBlock() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.TileEntityMetaGeneratedBlock");
            isHasClass_TileEntityMetaGeneratedBlock = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityMetaGeneratedBlock");
            isHasClass_TileEntityMetaGeneratedBlock = false;
        }
    }

    public static void isHasClass_AE2() {
        try {
            Class<?> clazz = Class.forName("appeng.tile.AEBaseTile");
            isHasClass_AE2 = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 AEBaseTile");
            isHasClass_AE2 = false;
        }
    }
}
