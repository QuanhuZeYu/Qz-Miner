package club.heiqi.qz_miner.core.founder;

import appeng.block.solids.OreQuartz;
import appeng.block.solids.OreQuartzCharged;
import bartworks.system.material.BWMetaGeneratedOres;
import bartworks.system.material.BWMetaGeneratedSmallOres;
import bartworks.system.material.TileEntityMetaGeneratedBlock;
import club.heiqi.qz_miner.utils.MessageUtils;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.BlockOresAbstract;
import gregtech.common.blocks.TileEntityOres;
import gtPlusPlus.core.block.base.BlockBaseOre;
import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class DeterminingIdentical {
    public static Logger LOG = LogManager.getLogger();

    public static boolean Identical(Block sBlock, int sMeta, @Nullable TileEntity sTile, Vector3i pos, EntityPlayer player) {
        if (!hasCheck) checkCompatibility();

        Block thisBlock = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        int thisMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        TileEntity thisTile = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        if (!sBlock.equals(thisBlock) || sMeta != thisMeta)
            return false;

        // 格雷机器判断相同
        if (hasGregTechTileEntity &&
                sTile instanceof IGregTechTileEntity sMetaTile &&
                thisTile instanceof IGregTechTileEntity thisMetaTile
        ) {
            return sMetaTile.getMetaTileID() == thisMetaTile.getMetaTileID();
        }
        // 格雷矿石判断相同
        if (hasTileEntityOre &&
                sTile instanceof TileEntityOres sTileEntityOre &&
                thisTile instanceof TileEntityOres tTileEntityOre
        ) {
            return sTileEntityOre.mMetaData == tTileEntityOre.mMetaData;
        }
        // 判断BartWork
        if (hasTileEntityMetaGeneratedBlock &&
                sTile instanceof TileEntityMetaGeneratedBlock sBTEMGB &&
                thisTile instanceof TileEntityMetaGeneratedBlock tBTEMGB
        ) {
            return sBTEMGB.mMetaData == tBTEMGB.mMetaData;
        }

        // 判断普通Tile
        if (sTile != null && thisTile != null)
            return sTile.getBlockMetadata() == thisTile.getBlockMetadata();

        return true;
    }

    public static Set<String> collectOrePackage = new HashSet<>();
    public static boolean isOreBlock(Vector3i pos, EntityPlayer player) {
        if (!hasCheck) checkCompatibility();
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        // int meta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        // TileEntity tile = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        // 原版矿石
        if (block instanceof BlockOre || block instanceof BlockRedstoneOre) return true;

        if (hasBlockOresAbstract && block instanceof BlockOresAbstract) return true;                // GT矿石
        if (hasBWMetaGeneratedSmallOres && block instanceof BWMetaGeneratedSmallOres) return true;  // bart小矿石
        if (hasBWMetaGeneratedOres && block instanceof BWMetaGeneratedOres) return true;            // bart矿石
        if (hasBlockBaseOre && block instanceof BlockBaseOre) return true;                          // GTPP矿石

        // AE 矿石
        if (hasAEOreQuartz && hasAEOreQuartzCharged && block instanceof OreQuartz || block instanceof OreQuartzCharged)
            return true;

        String blockUnlocalizeName = block.getUnlocalizedName().toLowerCase();
        String packageName = block.getClass().getTypeName();
        if (blockUnlocalizeName.contains("ore")) {
            // 每次游戏 每种未收录的包只提示一次信息
            if (!collectOrePackage.contains(packageName)) {
                MessageUtils.sendPlayerMessage(
                        "发现可能未被收录的矿石类: 【"+ packageName +"】Mod正在测试阶段，发现此消息可上报issue在未来版本逐渐完善后可能消失",
                        player
                );
                collectOrePackage.add(packageName);
            }
            return true;
        }

        return false;
    }

    public static boolean hasCheck = false;
    public static void checkCompatibility() {
        hasCheck = true;
        hasGregTechTileEntity();
        hasTileEntityOre();
        hasTileEntityMetaGeneratedBlock();
        hasBlockOresAbstract();
        hasBWMetaGeneratedSmallOres();
        hasBWMetaGeneratedOres();
        hasAEOreQuartz();
        hasAEOreQuartzCharged();
        hasBlockBaseOre();
    }

    public static boolean hasGregTechTileEntity = false;
    public static void hasGregTechTileEntity() {
        try {
            Class<?> clazz = Class.forName("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
            hasGregTechTileEntity = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 IGregTechTileEntity");
            hasGregTechTileEntity = false;
        }
    }

    public static boolean hasTileEntityOre = false;
    public static void hasTileEntityOre() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.TileEntityOres");
            hasTileEntityOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityOres");
            hasTileEntityOre = false;
        }
    }

    public static boolean hasTileEntityMetaGeneratedBlock = false;
    public static void hasTileEntityMetaGeneratedBlock() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.TileEntityMetaGeneratedBlock");
            hasTileEntityOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityMetaGeneratedBlock");
            hasTileEntityOre = false;
        }
    }

    public static boolean hasBlockOresAbstract = false;
    public static void hasBlockOresAbstract() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.BlockOresAbstract");
            hasBlockOresAbstract = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BlockOresAbstract");
            hasBlockOresAbstract = false;
        }
    }

    public static boolean hasBWMetaGeneratedSmallOres = false;
    public static void hasBWMetaGeneratedSmallOres() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.BWMetaGeneratedSmallOres");
            hasBWMetaGeneratedSmallOres = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BWMetaGeneratedSmallOres");
            hasBWMetaGeneratedSmallOres = false;
        }
    }

    public static boolean hasBWMetaGeneratedOres = false;
    public static void hasBWMetaGeneratedOres() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.BWMetaGeneratedOres");
            hasBWMetaGeneratedOres = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BWMetaGeneratedOres");
            hasBWMetaGeneratedOres = false;
        }
    }

    public static boolean hasAEOreQuartz = false;
    public static void hasAEOreQuartz() {
        try {
            Class<?> clazz = Class.forName("appeng.block.solids.OreQuartz");
            hasAEOreQuartz = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 OreQuartz");
            hasAEOreQuartz = false;
        }
    }

    public static boolean hasAEOreQuartzCharged = false;
    public static void hasAEOreQuartzCharged() {
        try {
            Class<?> clazz = Class.forName("appeng.block.solids.OreQuartzCharged");
            hasAEOreQuartzCharged = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 OreQuartz");
            hasAEOreQuartzCharged = false;
        }
    }

    public static boolean hasBlockBaseOre =false;
    public static void hasBlockBaseOre() {
        try {
            Class<?> clazz = Class.forName("gtPlusPlus.core.block.base.BlockBaseOre");
            hasBlockBaseOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 BlockBaseOre");
            hasBlockBaseOre = false;
        }
    }
}
