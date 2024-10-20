package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public abstract class AbstractChainMiner extends AbstractMiner {
    public Set<Block> visited = new HashSet<>(); // 添加一个字段缓存访问过的方块
    public Set<ItemStack> droppedItems = new HashSet<>();

    @Override
    public void runTask(AbstractMiner miner, World world, EntityPlayer player, Point center) {
        // 修改的逻辑: 开始时在访问过的点中添加中心点
        miner.world = world;
        miner.player = player;
        miner.center = center;
        if(miner.currentState == TaskState.IDLE) {
            // =====修改部分========
            Block centerBlock = BlockMethodHelper.getBlock(world, center);
            visited.add(centerBlock);
            droppedItems.addAll(BlockMethodHelper.getDrops(world, player, center));
            // ====================
            miner.currentState = TaskState.Start;
            FMLCommonHandler.instance().bus().register(miner);
        } else {
            MY_LOG.LOG.info("当前任务正在执行, 拒绝请求");
        }
    }

    @Override
    public List<Point> taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        // 如果任务当前状态是 IDLE 注册Tick事件 开始任务
        // 开始阶段: 检测点是否达到上限|超过距离 获取Supplier -> 在限制时间内 -> 获取点 -> 检测点
        // 结束阶段: 挖掘获取到的点

        // 需要修改的逻辑: 判断要挖掘的点是否和挖掘过的方块(掉落物)是否相同
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
            // 修改部分
            Point add = null;
            for(Block visitedBlock : visited) {
                boolean dropIsSimilar = BlockMethodHelper.checkPointDropIsSimilarToStack(world, player, point, droppedItems);
                if(dropIsSimilar || BlockMethodHelper.checkTwoBlockIsSameOrSimlar(visitedBlock, world.getBlock(point.x, point.y, point.z))) {
                    ret.add(point);
                    add = point;
                    curPoint++;
                    break;
                }
            }
            if(add != null) visited.add(BlockMethodHelper.getBlock(world, add));
            // =====================
        }
        if(ret.isEmpty()) {
            currentState = TaskState.Complete;
            this.complete(); // 结束任务
            return null;
        }

        currentState = TaskState.End; // 获取点后进入结束阶段
        return ret;
    }

    @Override
    public void complete() {
        visited.clear();
        super.complete();
    }
}
