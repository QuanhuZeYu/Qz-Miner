package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public abstract class AbstractMiner {
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

    public void runTask(AbstractMiner miner, World world, EntityPlayer player, Point center) {
        miner.world = world;
        miner.player = player;
        miner.center = center;
        if(miner.currentState == TaskState.IDLE) {
            miner.currentState = AbstractMiner.TaskState.Start;
            FMLCommonHandler.instance().bus().register(miner);
        } else {
            MY_LOG.LOG.info("当前任务正在执行, 拒绝请求");
        }
    }

    public List<Point> taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        // 如果任务当前状态是 IDLE 注册Tick事件 开始任务
        // 开始阶段: 检测点是否达到上限|超过距离 获取Supplier -> 在限制时间内 -> 获取点 -> 检测点
        // 结束阶段: 挖掘获取到的点
        if(center == null || world == null || player == null) return null;
        if(blockCount > Config.blockLimit) {
            currentState = TaskState.Complete;
            this.complete();
            return null;
        }
        List<Point> ret = new ArrayList<>();
        Supplier<Point> pointSupplier = getPoint_supplier(startPoint, Config.radiusLimit, Config.blockLimit);
        long startTime = System.currentTimeMillis();
        int curPoint = 0;
        while(System.currentTimeMillis() - startTime < taskTimeLimit && curPoint < Config.perTickBlockMine) {
            Point point = pointSupplier.get();
            if(point == null) {
                currentState = TaskState.End; // 当前阶段完成任务, 交给下一个阶段继续执行
                break;
            }
            boolean inRadius = PointMethodHelper.checkPointIsInBox(point, startPoint, Config.radiusLimit);
            if(!inRadius) continue;
            boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
            if(!isValid) continue;
            boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
            if(!canHarvest) continue;
            ret.add(point);
            curPoint++;
        }
        if(ret.isEmpty()) {
            currentState = TaskState.Complete;
            this.complete(); // 结束任务
            return null;
        }

        currentState = TaskState.End; // 获取点后进入结束阶段
        return ret;
    }

    public void taskEndPhase() {
        if(cache.isEmpty()) {
            complete();
            return;
        } else {
            while (!cache.isEmpty()) {
                Point point = cache.remove(0);
                boolean inRadius = PointMethodHelper.checkPointIsInBox(point, center, Config.radiusLimit);
                if(!inRadius) continue;
                boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
                if(!isValid) continue;
                boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
                if(!canHarvest) continue;
                BlockMethodHelper.tryHarvestBlock(world, (EntityPlayerMP) player, point);
                blockCount++;
            }
            currentState = TaskState.Start; // 继续开始任务
        }
    }

    public void complete() {
        MY_LOG.LOG.info("卸载实例");
        blockCount = 0;
        currentState = TaskState.IDLE;
        FMLCommonHandler.instance().bus().unregister(this);
    };

    /**
     * 这个方法要重写并添加Event注解才有效!!!!!
     */
    public void onTick(TickEvent.ServerTickEvent event) {
        switch(event.phase) {
            case START -> {
                if(currentState != TaskState.Start) return;
                List<Point> validPoints = taskStartPhase(world, player, center);
                if(validPoints == null) {
                    complete();
                    return;
                };
                cache.addAll(validPoints);
            }
            case END ->  {
                if(currentState != TaskState.End) return;
                taskEndPhase();
            }
        }
    }

    abstract public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit);
}
