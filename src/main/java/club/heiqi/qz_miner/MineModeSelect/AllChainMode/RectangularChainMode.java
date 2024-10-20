package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 该模式通过矿物词典进行连锁挖掘
 */
public class RectangularChainMode implements MinerChain{
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

    public static void runTask(RectangularChainMode miner, World world, EntityPlayer player, Point center) {
        miner.world = world;
        miner.player = player;
        miner.center = center;
        if(miner.currentState == TaskState.IDLE) {
            miner.currentState = TaskState.Start;
            MY_LOG.LOG.info("注册事件");
            FMLCommonHandler.instance().bus().register(miner);
        } else {
            MY_LOG.LOG.info("当前任务正在执行, 拒绝请求");
        }
    }

    public List<Point> taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        MY_LOG.LOG.info("开始任务");
        // 如果任务当前状态是 IDLE 注册Tick事件 开始任务
        // 开始阶段: 检测点是否达到上限|超过距离 获取Supplier -> 在限制时间内 -> 获取点 -> 检测点
        // 结束阶段: 挖掘获取到的点
        if(center == null || world == null || player == null) return null;
        if(blockCount > Config.blockLimit) {
            currentState = TaskState.Complete;
            this.complete();
            return null;
        }
        long startTime = System.currentTimeMillis();
        List<Point> ret = new ArrayList<>();
        Supplier<Point> pointSupplier = getPoint_supplier(startPoint, Config.radiusLimit, Config.blockLimit);
        int curPoint = 0;
        while(System.currentTimeMillis() - startTime < taskTimeLimit && curPoint < Config.perTickBlockMine) {
            Point point = pointSupplier.get();
            if(point == null) {
                currentState = TaskState.End; // 当前阶段完成任务, 交给下一个阶段继续执行
                break;
            }
            boolean inRadius = BlockMethodHelper.checkPointIsInBox(point, startPoint, Config.radiusLimit);
            if(!inRadius) continue;
            boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
            if(!isValid) continue;
            boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
            if(!canHarvest) continue;
            ret.add(point);
            blockCount++;
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
                MY_LOG.LOG.info("挖掘点: {}", point);
                BlockMethodHelper.tryHarvestBlock(world, (EntityPlayerMP) player, point);
            }
            currentState = TaskState.Start; // 继续开始任务
        }
    }

    public void complete() {
        MY_LOG.LOG.info("卸载实例");
        blockCount = 0;
        currentState = TaskState.IDLE;
        FMLCommonHandler.instance().bus().unregister(this);
    }

    @SubscribeEvent
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

    /**
     * 从中心方块开始获取临近的6方块
     * 判断是否是类似块 -- 需要一套白名单系统 可配置文件
     * @param world
     * @param player
     * @param x
     * @param y
     * @param z
     * @return
     */
    @Override
    public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z) {
        return null;
    }

    @Override
    public Supplier<Point> getPoint_supplier(World world, EntityPlayer player, Point center, int radius, int blockLimit) {
        final List<Point> cache = new ArrayList<>();
        final Set<Point> visited = new HashSet<>();
        final int[] distance = new int[]{0};
        final int[] blockCount = new int[]{0};
        cache.add(center);

        return new Supplier<Point>() {
            @Override
            public Point get() {
//                try {
//                    if(blockCount[0] >= blockLimit) return null;
//                    if(cache.isEmpty()) return null; // 缓存为空, 认定链路结束
//                    Point curPoint = cache.remove(0); // 获取到点
//                    if(curPoint == null) return null;
//                    if(visited.contains(curPoint)) return get();
//                    visited.add(curPoint); // 标记为已访问
//
//                    // 随挖随补
//                    List<Point> surroundingPoints = BlockMethodHelper.getSurroundPointsEnhanced(world, curPoint, Config.chainRange);// 获取范围内临近的所有点
//                    for(Point point : surroundingPoints) { // 如果点未访问过 检查点是否在范围内, 剔除掉超过范围的点
//                        if(!visited.contains(point) && BlockMethodHelper.checkPointBlockIsValid(world, point)) {
//                            cache.add(point);
//                        }
//                    }
//
//                    Block curPointBlock = world.getBlock(curPoint.x, curPoint.y, curPoint.z);
//                    int meta = world.getBlockMetadata(curPoint.x, curPoint.y, curPoint.z);
//                    if (BlockMethodHelper.checkPointBlockIsValid(world, curPoint)
//                        && curPointBlock.canHarvestBlock(player, curPointBlock.getDamageValue(world, curPoint.x, curPoint.y, curPoint.z))) {
//                        blockCount[0]++;
//                        return curPoint;
//                    } else {
//                        return get();
//                    }
//                } catch (Exception e) {
//                    MY_LOG.LOG.error("getPoint_supplier 函数错误", e);
//                    return null;
//                }
                while(true) {
                    try {
                        if (blockCount[0] >= blockLimit) return null;
                        if (cache.isEmpty()) return null; // 缓存为空, 认定链路结束
                        Point curPoint = cache.remove(0); // 获取到点
                        if (curPoint == null) return null;
                        if (visited.contains(curPoint)) continue;
                        visited.add(curPoint); // 标记为已访问

                        // 随挖随补
                        List<Point> surroundingPoints = BlockMethodHelper.getSurroundPointsEnhanced(world, curPoint, Config.chainRange);// 获取范围内临近的所有点
                        for (Point point : surroundingPoints) { // 如果点未访问过 检查点是否在范围内, 剔除掉超过范围的点 -- 需要剔除更多的点, cache太多了
                            Block block = world.getBlock(point.x, point.y, point.z);
                            boolean isInRadius = BlockMethodHelper.checkPointIsInBox(point, center, radius);
                            if (!visited.contains(point)
                                && BlockMethodHelper.checkPointBlockIsValid(world, point)
                                && isInRadius
                                && block.canHarvestBlock(player, block.getDamageValue(world, point.x, point.y, point.z))
                            ) {
                                cache.add(point);
                                MY_LOG.LOG.info("当前cache大小 {}", cache.size());
                            } else {
                                visited.add(point); // 确认无效的点
                            }
                        }

                        Block curPointBlock = world.getBlock(curPoint.x, curPoint.y, curPoint.z);
                        blockCount[0]++;
                        return curPoint;
                    }catch (Exception e) {
                        MY_LOG.LOG.error("getPoint_supplier 函数错误", e);
                        break;
                    }
                }
                return null;
            }
        };
    }

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
