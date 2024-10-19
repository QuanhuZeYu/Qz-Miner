package club.heiqi.qz_miner.Core;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.AllChainMode.RectangularChainMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.CenterRectangularMode;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerModeProxy;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.RangeModeEnum;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

import java.util.*;
import java.util.function.Supplier;

public class CoreLogic_BlockBreaker {
    public World world = null;
    public EntityPlayer player = null;

    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent breakEvent) {
        if(breakEvent.world.isRemote) {
            MY_LOG.LOG.warn("客户端下阻止使用");
            return;
        }
        if(!AllPlayerStatue.getStatue(breakEvent.getPlayer().getUniqueID()).minerIsOpen) {
            return;
        }
        // 获取破坏方块的坐标
        int x = breakEvent.x;
        int y = breakEvent.y;
        int z = breakEvent.z;

        this.world = breakEvent.world;
        this.player = breakEvent.getPlayer();

        MinerModeProxy minerModeProxy = MinerModeProxy.INSTANCE;

//        Point[] blockList = MinerModeProxy.getBlockList(this.world, player, x, y, z);
//        DebugPrint.printBlockList(world, blockList); // 打印调试信息
//        breakBlock(breakEvent, player, x, y, z);
    }

    /**
     * 破坏方块核心函数
     *
     * @param points 待破坏的方块坐标列表
     * @param player 玩家
     * @param pX     玩家坐标X
     * @param pY     玩家坐标Y-高度
     * @param pZ     玩家坐标Z
     */
    public void breakBlock(BlockEvent.BreakEvent event, Point[] points, EntityPlayer player, double pX, double pY, double pZ) {
        int i = 0;
        for (Point point : points) {
            i++;
            Block block = this.world.getBlock(point.x, point.y, point.z);
            int meta = this.world.getBlockMetadata(point.x, point.y, point.z);
            if (block != Blocks.air && block.canHarvestBlock(player, meta)) {
                ArrayList<ItemStack> drops = block.getDrops(this.world, point.x, point.y, point.z, meta, 0);
                BlockMethodHelper.tryHarvestBlock(event, world, (EntityPlayerMP) player, point.x, point.y, point.z);
            }
        }
    }

    public void breakBlock(BlockEvent.BreakEvent event, EntityPlayer player, int x, int y, int z) {
        Point center = new Point(x, y, z);
        Supplier<Point> pointSupplier = MinerModeProxy.rangeModeSelect.get(2).getPoint_supplier(world, player, center, Config.radiusLimit, Config.blockLimit);
        Point thisP;
        while ((thisP = pointSupplier.get()) != null) {  // 取一次,挖一次
            BlockMethodHelper.tryHarvestBlock(event, world, (EntityPlayerMP) player, thisP.x, thisP.y, thisP.z);
        }
    }

//    public void harvestBlock(World worldIn, EntityPlayer player, int x, int y, int z, int meta) {
//        Block block = worldIn.getBlock(x, y, z);
//        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1);
//        player.addExhaustion(0.025F);
//
//        if (block.canSilkHarvest(worldIn, player, x, y, z, meta) && EnchantmentHelper.getSilkTouchModifier(player)) {
//            ArrayList<ItemStack> items = new ArrayList<ItemStack>();
//            meta = Item.getItemFromBlock(block).getHasSubtypes() ? meta : 0;
//            ItemStack itemstack = new ItemStack(Item.getItemFromBlock(block), 1, meta);
//            items.add(itemstack);
//            ForgeEventFactory.fireBlockHarvesting(items, worldIn, block, x, y, z, meta, 0, 1.0f, true, player);
//            for (ItemStack item : items) {
//                worldIn.spawnEntityInWorld(new EntityItem(worldIn, x, y, z, item));
//            }
//        }
//        else {
//            int i1 = EnchantmentHelper.getFortuneModifier(player);
//            block.dropBlockAsItem(worldIn, x, y, z, meta, i1);
//        }
//    }

}
