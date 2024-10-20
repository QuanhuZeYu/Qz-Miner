package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 该模式通过矿物词典进行连锁挖掘
 */
public class RectangularChainMode extends AbstractChainMiner {
    public World world;
    public EntityPlayer player;

    public Point center;

    public static int taskTimeLimit = 30; // 单个任务执行限制20ms, 不保证一定小于, 但是在检测时若超过10ms则停止任务让出进程

    public TaskState currentState = TaskState.IDLE;
    public int blockCount = 0;
    public List<Point> cache = new ArrayList<>();

    public enum TaskState {
        IDLE,
        Start,
        End,
        Complete;
    }

    @Override
    public List<Point> taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        return super.taskStartPhase(world, player, startPoint);
    }

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
