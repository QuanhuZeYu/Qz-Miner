package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;
import java.util.function.Supplier;

public interface MinerChain {
    @Deprecated
    abstract public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z);

    abstract public Supplier<Point> getPoint_supplier(World world, EntityPlayer player, Point center, int radius, int blockLimit);
}
