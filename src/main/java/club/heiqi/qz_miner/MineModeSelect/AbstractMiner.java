package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.PointFonder.PointFonder;
import club.heiqi.qz_miner.MineModeSelect.PointFonder.PointFonder_Rectangular;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;

import java.util.*;

import static club.heiqi.qz_miner.MY_LOG.printMessage;

public abstract class AbstractMiner {
    public World world;
    public EntityPlayer player;

    public Point center;
    public Block centerBlock;
    public int centerBlockMeta;
    public Set<ItemStack> centerBlockDropItems = new HashSet<>();

    public static int taskTimeLimit = 20; // 单个任务执行限制20ms, 不保证一定小于, 但是在检测时若超过10ms则停止任务让出进程
    public TaskState currentState = TaskState.IDLE;
    public int blockCount = 0;
    public List<Point> cache = new ArrayList<>();  // 搜索点放入, 挖掘点取出所用缓存
    public List<Point> cacheMined = new ArrayList<>(); // 记录挖掘过的点
    public PointFonder pointFonder;

    public enum TaskState {
        IDLE,
        Start,
        End,
        Complete;
    }

    public boolean addPointFonder() {
        return false;
    };

    /**
     * 请重载该方法 并添加搜索者的赋值!!
     */
    public void runTask(AbstractMiner miner, World world, EntityPlayer player, Point center) {
        miner.world = world;
        miner.player = player;
        miner.center = center;
        miner.centerBlock = BlockMethodHelper.getBlock(world, center);  // 记录Block类信息
        miner.centerBlockMeta = world.getBlockMetadata(center.x, center.y, center.z);  // 记录Block的Meta
        List<ItemStack> drops = BlockMethodHelper.getDrops(world, player, center);
        centerBlockDropItems.addAll(drops); // 记录Block的掉落物
//        pointFonder = new PointFonder_Rectangular(center);  // 搜索者
        if(!addPointFonder()) {
            complete();
            return;
        }
        if(miner.currentState == TaskState.IDLE) {
            miner.currentState = AbstractMiner.TaskState.Start;
            FMLCommonHandler.instance().bus().register(miner);
        } else {
//            MY_LOG.LOG.info("当前任务正在执行, 拒绝请求");
        }
    }

    public void taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        // 开始阶段任务只能是 update 然后判断update是否成功, 失败则代表点已经遍历完毕
        if(center == null || world == null || player == null) return;
        pointFonder.update();
        Collection<Point> ret = pointFonder.getAll();
        if(ret == null || ret.isEmpty()) {
            currentState = TaskState.End;
            return; // 点已经搜索完毕, 交给挖掘任务
        }
        cache.addAll(ret);
        // 任务时间结束, 或者 缓存点数量达到上限
        currentState = TaskState.End; // 获取点后进入结束阶段
    }

    public void taskEndPhase() {
        if(!AllPlayerStatue.getStatue(player.getUniqueID()).minerIsOpen || currentState == TaskState.IDLE) {
//            printMessage("当前挖掘任务被取消 或者已经完成");
            complete();
        }else {
            long lastTime = System.currentTimeMillis();
            while (!cache.isEmpty()) {
                Point point = cache.remove(0);
                boolean inRadius = PointMethodHelper.checkPointIsInBox(point, center, Config.radiusLimit);
                if(!inRadius) {
//                    printMessage("2.当前点不在BOX内");
                    continue;
                }
                boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
                if(!isValid) {
//                    printMessage("2.当前点是空气或者液体");
                    continue;
                }
                boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
                if(!canHarvest) {
//                    printMessage("2.当前点无法收获");
                    continue;
                }
                if(excludeLogic(point)) continue;

                BlockMethodHelper.tryHarvestBlock(world, (EntityPlayerMP) player, point);
                blockCount++;

                if(checkTimeOut(lastTime)) break;
                if(blockCount > Config.blockLimit) {
                    complete();
                    return;
                }
            }
            currentState = TaskState.Start; // 继续开始任务
        }
    }

    public void complete() {
        printMessage("已挖掘 "+ blockCount + " 个方块");
        blockCount = 0;
        currentState = TaskState.IDLE;
        cache.clear();
        centerBlockDropItems.clear();
        center = null;
        centerBlock = null;
        centerBlockMeta = 0;
        world = null;
        player = null;
        if(pointFonder != null) pointFonder.cache.clear();
        printMessage("任务已完成");
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
                printMessage("搜寻点任务完成");
            }
            case END ->  {
                if(currentState != TaskState.End) return;
                taskEndPhase();
                printMessage("挖掘点任务完成");
            }
        }
    }

    public boolean checkTimeOut(long lastTime) {
        long now = System.currentTimeMillis();
        return now - lastTime > taskTimeLimit;
    }

    /**
     * 排除逻辑, 返回true排除掉该点
     */
    public boolean excludeLogic(Point curPoint) {return false;}
}
