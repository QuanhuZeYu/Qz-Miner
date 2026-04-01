package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import ic2.core.crop.TileEntityCrop;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class CropFounder extends BasePositionFounder {
    public CropFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
        setName("作物搜索器");
    }

    @Override
    public boolean checkCanAdd(Vector3i pos) {
        if (foundedPositions.contains(pos)) {
            // LOG.info("重复的点");
            return false;
        }
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        TileEntity tile = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);
        if (block.equals(Blocks.air) || block.getMaterial().isLiquid()) {
            return false;
        }
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);

        // 检查是否是作物
        if (block instanceof BlockCrops) return true;
        if (tile instanceof TileEntityCrop) return true;
        return false;
    }
}
