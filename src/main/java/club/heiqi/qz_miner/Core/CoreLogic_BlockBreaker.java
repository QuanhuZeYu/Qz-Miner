package club.heiqi.qz_miner.Core;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.MinerModeProxy;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

import java.util.*;

import static net.minecraft.block.Block.getIdFromBlock;

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

        double pX = Math.floor(player.posX);
        double pY = Math.floor(player.posY) + 0.5d;
        double pZ = Math.floor(player.posZ);

        Point[] blockList = MinerModeProxy.getBlockList(this.world, player, x, y, z);
//        DebugPrint.printBlockList(world, blockList); // 打印调试信息
        breakBlock(breakEvent, blockList, player, pX, pY, pZ);
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
                tryHarvestBlock(event, world, (EntityPlayerMP) player, point.x, point.y, point.z);
            }
        }
    }

    public void tryHarvestBlock(BlockEvent.BreakEvent event, World world, EntityPlayerMP player, int x, int y, int z) {
        ItemInWorldManager manager = player.theItemInWorldManager;
        ItemStack stack = player.getCurrentEquippedItem();
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        manager.theWorld.playAuxSFXAtEntity(player,2001,x,y,z, getIdFromBlock(block) + meta << 12);
        boolean removeBlockSuccess = false;
        if(manager.isCreative()) {
            block.onBlockHarvested(world, x, y, z, meta, player);
            removeBlockSuccess = block.removedByPlayer(world, player, x, y, z, false);
            if(removeBlockSuccess) {
                block.onBlockDestroyedByPlayer(world, x, y, z, meta);
            }
            manager.thisPlayerMP.playerNetServerHandler.sendPacket(new S23PacketBlockChange(x, y, z, world));
        } else {
            ItemStack heldItem = manager.thisPlayerMP.getCurrentEquippedItem();
            boolean canHarvestBlock = block.canHarvestBlock(player, meta);
            if(heldItem != null) {
                heldItem.func_150999_a(world, block, x, y, z, player);
                if(heldItem.stackSize == 0) {
                    manager.thisPlayerMP.destroyCurrentEquippedItem();
                }
            }
            removeBlockSuccess = removeBlock(x, y, z, removeBlockSuccess);
            if(removeBlockSuccess && canHarvestBlock) {
                block.harvestBlock(world, player, x, y, z, meta);
            }
        }
        // Drop experience
        if (!manager.isCreative() && removeBlockSuccess)
        {
            block.dropXpOnBlockBreak(world, x, y, z, event.getExpToDrop());
        }

    }

    public boolean removeBlock(int x, int y, int z, boolean canHarvest) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        block.onBlockHarvested(world, x, y, z, meta, player);
        boolean canRemoveByPlayer = block.removedByPlayer(world, player, x, y, z, canHarvest);
        if(canRemoveByPlayer) {
            block.onBlockDestroyedByPlayer(world, x, y, z, meta);
        }
        return canRemoveByPlayer;
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
