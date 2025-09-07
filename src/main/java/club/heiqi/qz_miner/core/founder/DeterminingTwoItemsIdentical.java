package club.heiqi.qz_miner.core.founder;

import bartworks.system.material.TileEntityMetaGeneratedBlock;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import javax.annotation.Nullable;

public class DeterminingTwoItemsIdentical {
    public static Logger LOG = LogManager.getLogger();

    public static boolean Identical(Block sBlock, int sMeta, @Nullable TileEntity sTile, Vector3i pos, EntityPlayer player) {
        if (!hasCheck) checkCompatibility();

        Block thisBlock = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        int thisMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        TileEntity thisTile = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        if (!sBlock.equals(thisBlock) || sMeta != thisMeta)
            return false;

        // 格雷机器判断相同
        if (isHasGregTechTileEntity &&
                sTile instanceof IGregTechTileEntity sMetaTile &&
                thisTile instanceof IGregTechTileEntity thisMetaTile
        ) {
            return sMetaTile.getMetaTileID() == thisMetaTile.getMetaTileID();
        }
        // 格雷矿石判断相同
        if (isHasTileEntityOre &&
                sTile instanceof TileEntityOres sTileEntityOre &&
                thisTile instanceof TileEntityOres tTileEntityOre
        ) {
            return sTileEntityOre.mMetaData == tTileEntityOre.mMetaData;
        }
        // 判断BartWork
        if (isTileEntityMetaGeneratedBlock &&
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

    public static boolean hasCheck = false;
    public static void checkCompatibility() {
        hasCheck = true;
        isHasGregTechTileEntity();
        isHasTileEntityOre();
        isTileEntityMetaGeneratedBlock();
    }

    public static boolean isHasGregTechTileEntity = false;
    public static void isHasGregTechTileEntity() {
        try {
            Class<?> clazz = Class.forName("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
            isHasGregTechTileEntity = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 IGregTechTileEntity");
            isHasGregTechTileEntity = false;
        }
    }

    public static boolean isHasTileEntityOre = false;
    public static void isHasTileEntityOre() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.TileEntityOres");
            isHasTileEntityOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityOres");
            isHasTileEntityOre = false;
        }
    }

    public static boolean isTileEntityMetaGeneratedBlock = false;
    public static void isTileEntityMetaGeneratedBlock() {
        try {
            Class<?> clazz = Class.forName("bartworks.system.material.TileEntityMetaGeneratedBlock");
            isHasTileEntityOre = true;
        } catch (ClassNotFoundException e) {
            LOG.warn("未检测到 TileEntityMetaGeneratedBlock");
            isHasTileEntityOre = false;
        }
    }
}
