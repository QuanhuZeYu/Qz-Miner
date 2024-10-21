package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static club.heiqi.qz_miner.MY_LOG.LOG;

/**
 * 该模式通过矿物词典进行连锁挖掘
 */
public class RectangularChainMode extends AbstractChainMiner {

    @Override
    public void taskEndPhase() {
        super.taskEndPhase();
    }

    @Override
    public void complete() {
        super.complete();
    }

    @Override
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        super.onTick(event);
    }

    @Override
    public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit) {
        final List<Point> cache = PointMethodHelper.getAllPointInBox(center, radius);

        return new Supplier<Point>() {
          @Override
          public Point get() {
              if(cache.isEmpty()) return null;
              return cache.remove(0);
          }
        };
    }
}
