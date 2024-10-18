package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;

public interface MinerChain {
    abstract public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z);
}
