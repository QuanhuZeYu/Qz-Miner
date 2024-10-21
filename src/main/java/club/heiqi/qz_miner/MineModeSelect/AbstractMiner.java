package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

import static club.heiqi.qz_miner.MY_LOG.LOG;

public abstract class AbstractMiner {
    public World world;
    public EntityPlayer player;

    public Point center;
    public Block centerBlock;
    public int centerBlockMeta;
    public Set<ItemStack> centerBlockDropItems = new HashSet<>();

    public static int taskTimeLimit = 50; // 单个任务执行限制20ms, 不保证一定小于, 但是在检测时若超过10ms则停止任务让出进程
    public TaskState currentState = TaskState.IDLE;
    public int blockCount = 0;
    public List<Point> cache = new ArrayList<>();
    public Supplier<Point> pointSupplier;

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
        miner.centerBlock = BlockMethodHelper.getBlock(world, center);
        miner.centerBlockMeta = world.getBlockMetadata(center.x, center.y, center.z);
        List<ItemStack> drops = BlockMethodHelper.getDrops(world, player, center);
        centerBlockDropItems.addAll(drops);
        pointSupplier = getPoint_supplier(center, Config.radiusLimit, Config.blockLimit);
        if(miner.currentState == TaskState.IDLE) {
            miner.currentState = AbstractMiner.TaskState.Start;
            FMLCommonHandler.instance().bus().register(miner);
        } else {
//            MY_LOG.LOG.info("当前任务正在执行, 拒绝请求");
        }
    }

    public void taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        // 如果任务当前状态是 IDLE 注册Tick事件 开始任务
        // 开始阶段: 检测点是否达到上限|超过距离 获取Supplier -> 在限制时间内 -> 获取点 -> 检测点
        // 结束阶段: 挖掘获取到的点
        if(center == null || world == null || player == null) return;
        if(blockCount > Config.blockLimit) {
            currentState = TaskState.Complete;
            this.complete();
            return;
        }
        List<Point> ret = new ArrayList<>();
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
            return;
        }

        currentState = TaskState.End; // 获取点后进入结束阶段
        cache.addAll(ret);
    }

    public void taskEndPhase() {
        if(!AllPlayerStatue.getStatue(player.getUniqueID()).minerIsOpen) {
            complete();
        }
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
//        MY_LOG.LOG.info("卸载实例");
        blockCount = 0;
        currentState = TaskState.IDLE;
        pointSupplier = null;
        cache.clear();
        centerBlockDropItems.clear();
        center = null;
        centerBlock = null;
        centerBlockMeta = 0;
        world = null;
        player = null;
        FMLCommonHandler.instance().bus().unregister(this);
    };

    /**
     * 这个方法要重写并添加Event注解才有效!!!!!
     */
    public void onTick(TickEvent.ServerTickEvent event) {
        switch(event.phase) {
            case START -> {
                if(currentState != TaskState.Start) return;
                taskStartPhase(world, player, center);
            }
            case END ->  {
                if(currentState != TaskState.End) return;
                taskEndPhase();
            }
        }
    }

    abstract public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit);
}
