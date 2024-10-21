package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public abstract class AbstractChainMiner extends AbstractMiner {

    @Override
    public void taskStartPhase(World world, EntityPlayer player, Point startPoint) {
        // 如果任务当前状态是 IDLE 注册Tick事件 开始任务
        // 开始阶段: 检测点是否达到上限|超过距离 获取Supplier -> 在限制时间内 -> 获取点 -> 检测点
        // 结束阶段: 挖掘获取到的点

        // 需要修改的逻辑: 判断要挖掘的点是否和挖掘过的方块(掉落物)是否相同
        if(center == null || world == null || player == null) return;
        if(blockCount > Config.blockLimit) { // 如果挖掘计数器超过限制, 则任务完成
//            LOG.info("因达到数量限制 停止连锁");
            currentState = TaskState.Complete;
            this.complete();
            return;
        }
        List<Point> ret = new ArrayList<>(); // 需要返回的数组
        long startTime = System.currentTimeMillis();
        int curPoint = 0;
        while(true) {
            if(System.currentTimeMillis() - startTime > taskTimeLimit) {
                currentState = TaskState.End; // 当前阶段完成任务, 交给下一个阶段继续执行
                break;
            }
            if(curPoint > Config.perTickBlockMine) {
                currentState = TaskState.End; // 当前阶段完成任务, 交给下一个阶段继续执行
                break;
            }

            Point point = pointSupplier.get(); // 从范围内的点取出一个
            if(point == null) { // 如果取出的点是null, 表示区域已经遍历完 任务完成
                MY_LOG.printToChatMessage(world.isRemote, player.getDisplayName()+"范围内点已经全部遍历完成");
                currentState = TaskState.End; // 当前阶段完成任务, 交给下一个阶段继续执行
                break; // 打断循环 提交结果
            }
            boolean inRadius = PointMethodHelper.checkPointIsInBox(point, startPoint, Config.radiusLimit);
            if(!inRadius) continue; // 超出范围,寻找下一个点
            boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
            if(!isValid) continue;  // 不可破坏方块, 寻找下一个
            boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
            if(!canHarvest) continue; // 不可采集, 寻找下一个
            // 修改部分
            curPoint = excludeLogic(world, player, point, ret, curPoint); // 对当前计数器进行累加
            // =====================
        }
        if(ret.isEmpty()) {
//            LOG.info("没有可挖掘的点,任务完成");
            currentState = TaskState.Complete;
            this.complete(); // 结束任务
            return;
        }

        cache.addAll(ret);
//        LOG.info("缓存点数量: {}", ret.size());
    }

    @Override
    public void taskEndPhase() {
        if(cache.isEmpty()) {
//            LOG.info("没有可供挖掘的点, 任务结束");
            complete();
            return;
        } else {
            // 挖掘逻辑
            while (!cache.isEmpty()) { // cache不为空继续挖掘 -- cache 数量一般是 perTickBlockMine 数值
                Point point = cache.remove(0); // 取出一个点
                // 二次校验该点
                boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
                if(!isValid) continue;
                boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
                if(!canHarvest) continue;
                BlockMethodHelper.tryHarvestBlock(world, (EntityPlayerMP) player, point);
                blockCount++;
            }
//            LOG.info("当前已挖掘数量: {}", blockCount);
            currentState = TaskState.Start; // 继续开始任务
        }
    }

    /**
     * 从已经搜寻到的点中排除的逻辑
     */
    public int excludeLogic(World world, EntityPlayer player, Point checkP, List<Point> ret, int curPoints) {
        boolean isSimilar = BlockMethodHelper.checkPointDropIsSimilarToStack_IncludeCrushedOre(world, player, checkP, centerBlockDropItems);
        if(isSimilar || BlockMethodHelper.checkTwoBlockIsSameOrSimilar(centerBlock, world.getBlock(checkP.x, checkP.y, checkP.z))) {
            ret.add(checkP);
            curPoints++;
        }
        return curPoints;
    }
}
