package club.heiqi.qz_miner.MineModeSelect.MethodHelper;

import club.heiqi.qz_miner.Storage.Statue;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import club.heiqi.qz_miner.Util.CheckCompatibility;
import gregtech.common.blocks.BlockOresAbstract;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.stats.StatList;
import net.minecraft.world.World;
import club.heiqi.qz_miner.CustomData.Point;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.oredict.OreDictionary;


import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

import static net.minecraft.block.Block.getIdFromBlock;

public class BlockMethodHelper {
    public static Block getBlock(World world, Point point) {
        return world.getBlock(point.x, point.y, point.z);
    }

    /**
     * 注意!!!! 该方法需要方块存在
     * @param world
     * @param player
     * @param point
     * @return
     */
    public static List<ItemStack> getDrops(World world, EntityPlayer player, Point point) {
        Block block = getBlock(world, point);
        int fortune = EnchantmentHelper.getFortuneModifier(player);
        int meta = world.getBlockMetadata(point.x, point.y, point.z);
        return block.getDrops(world, point.x, point.y, point.z, meta, fortune);
    }

    /**
     * 检查两个物品是否相似, 包括CrushedOre--碎矿 选取的范围更广
     * @param world
     * @param player
     * @param targetPoint
     * @param drops
     * @return
     */
    public static boolean checkPointDropIsSimilarToStack_IncludeCrushedOre(World world, EntityPlayer player, Point targetPoint, Set<ItemStack> drops) {
        List<ItemStack> targetDrops = getDrops(world, player, targetPoint);
        for (ItemStack targetDrop : targetDrops) {
            for (ItemStack drop : drops) {
                if(drop.equals(targetDrop)) {
                    return true; // 完全相同提前返回
                }
                if (Objects.equals(drop.getUnlocalizedName(), targetDrop.getUnlocalizedName())) {
                    return true;
                }
                if (checkTwoItemIsSimilar_IncludeCrushedOre(drop, targetDrop)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查两个物品是否相似 主要包含 ore rawore
     * @param world
     * @param player
     * @param targetPoint
     * @param drops
     * @return
     */
    public static boolean checkPointDropIsSimilarToStack(World world, EntityPlayer player, Point targetPoint, Set<ItemStack> drops) {
        List<ItemStack> targetDrops = getDrops(world, player, targetPoint);
        for (ItemStack targetDrop : targetDrops) {
            for (ItemStack drop : drops) {
                if(drop.equals(targetDrop)) return true; // 完全相同提前返回
                if (checkTwoItemIsSimilar(drop, targetDrop)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean checkTwoBlockIsSameOrSimilar(Block blockA, Block blockB) {
        if(CheckCompatibility.isHasClass_BlockOresAbstract){
            if(blockA instanceof BlockOresAbstract && blockB instanceof BlockOresAbstract) {
                return true;
            }
        }
        int[] dictA = OreDictionary.getOreIDs(new ItemStack(blockA));
        int[] dictB = OreDictionary.getOreIDs(new ItemStack(blockB));
        for(int dA : dictA) {
            String dA_OreName = OreDictionary.getOreName(dA);
            for(int dB : dictB) {
                String dB_OreName = OreDictionary.getOreName(dB);
                if(dA_OreName.equals(dB_OreName)) {
                    return true;
                }
                if(dA_OreName.toLowerCase().startsWith("ore") && dB_OreName.toLowerCase().startsWith("ore")) return true;
            }
        }
        return false;
    }

    public static boolean checkTwoItemIsSimilar_IncludeCrushedOre(ItemStack center, ItemStack itemB) {
        boolean result = false;
        int[] dictCenter = OreDictionary.getOreIDs(center);
        int[] dictB = OreDictionary.getOreIDs(itemB);
        for(int idCenter : dictCenter) {
            String oreName = OreDictionary.getOreName(idCenter);
            if(oreName.startsWith("ore") || oreName.contains("blockores") || oreName.toLowerCase().startsWith("rawore")) {
                for(int idB : dictB) {
                    String oreNameB = OreDictionary.getOreName(idB);
                    if(oreNameB.startsWith("ore") || oreNameB.contains("blockores") || oreNameB.toLowerCase().startsWith("rawore")) {
                        result = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    public static boolean checkTwoItemIsSimilar(ItemStack center, ItemStack itemB) {
        boolean result = false;
        int[] dictCenter = OreDictionary.getOreIDs(center);
        int[] dictB = OreDictionary.getOreIDs(itemB);
        for(int idCenter : dictCenter) {
            String oreName = OreDictionary.getOreName(idCenter);
            if(oreName.startsWith("ore") || oreName.toLowerCase().startsWith("rawore")) {
                for(int idB : dictB) {
                    String oreNameB = OreDictionary.getOreName(idB);
                    if(oreNameB.startsWith("ore") || oreNameB.toLowerCase().startsWith("rawore")) {
                        result = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    public static boolean checkPointBlockIsValid(World world, Point point) {
        Block block = world.getBlock(point.x, point.y, point.z);
        return block != Blocks.air && !block.getMaterial().isLiquid();
    }

    /**
     * 获取立方体外侧面所在所有点列表
     * @param YpXpZp 顶部右侧前方
     * @param YsXsZs 底部左侧后方
     * @return 所有外侧面点的列表
     */
    public static List<Point> getOutBoundOfPoint(Point YpXpZp, Point YsXsZs) {
        List<Point> points = new ArrayList<>();

        int minX = Math.min(YpXpZp.x, YsXsZs.x) - 1;
        int maxX = Math.max(YpXpZp.x, YsXsZs.x) + 1;
        int minY = Math.min((Math.min(YpXpZp.y, YsXsZs.y) - 1), 0);
        int maxY = Math.max((Math.max(YpXpZp.y, YsXsZs.y) + 1), 255);
        int minZ = Math.min(YpXpZp.z, YsXsZs.z) - 1;
        int maxZ = Math.max(YpXpZp.z, YsXsZs.z) + 1;

        // 添加六个面的所有边界点
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                points.add(new Point(x, y, minZ));
                points.add(new Point(x, y, maxZ));
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                points.add(new Point(minX, y, z));
                points.add(new Point(maxX, y, z));
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                points.add(new Point(x, minY, z));
                points.add(new Point(x, maxY, z));
            }
        }
        return points;
    }

    /**
     * 获取立方体外侧面所在所有点列表
     *
     * @param center 中心点
     * @param radius 半径（每个方向上的偏移量）
     */
    public static void getOutBoundOfPoint(List<Point> pointList, Point center, int radius) {

        int minX = center.x - radius;
        int maxX = center.x + radius;
        int minY = center.y - radius;
        int maxY = center.y + radius;
        int minZ = center.z - radius;
        int maxZ = center.z + radius;

        // 添加六个面的所有边界点
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                // 前后面
                pointList.add(new Point(x, y, minZ));
                pointList.add(new Point(x, y, maxZ));
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 左右面
                pointList.add(new Point(minX, y, z));
                pointList.add(new Point(maxX, y, z));
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 上下面
                pointList.add(new Point(x, minY, z));
                pointList.add(new Point(x, maxY, z));
            }
        }
    }

    // region 方块破坏逻辑
    /**
     *
     */
    public static void tryHarvestBlock(World world, EntityPlayerMP player, Point point) {
        int x = point.x;
        int y = point.y;
        int z = point.z;
        ItemInWorldManager manager = player.theItemInWorldManager;  // 玩家管理器
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        manager.theWorld.playAuxSFXAtEntity(player,2001,x,y,z, getIdFromBlock(block) + meta << 12);
        // 破坏之前先获取掉落物列表
        Statue playerStatue = AllPlayerStatue.getStatue(player.getUniqueID());
        List<ItemStack> dropsItem = block.getDrops(world, x, y, z, meta, EnchantmentHelper.getFortuneModifier(player));
        playerStatue.dropsItem.clear(); // 添加之前先清理掉
        playerStatue.dropsItem.addAll(dropsItem);
        boolean removeBlockSuccess = false;
        if(manager.isCreative()) { // 创造逻辑
            block.onBlockHarvested(world, x, y, z, meta, player);
            removeBlockSuccess = block.removedByPlayer(world, player, x, y, z, false);
            if(removeBlockSuccess) { // 破坏成功了
                BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(x, y, z, world, block, meta,
                    EnchantmentHelper.getFortuneModifier(player), 1.0f, new ArrayList<>(dropsItem), player, false);
                MinecraftForge.EVENT_BUS.post(event); // 发送收获方块事件
            }
            manager.thisPlayerMP.playerNetServerHandler.sendPacket(new S23PacketBlockChange(x, y, z, world));
        } else { // 生存逻辑
            ItemStack heldItem = manager.thisPlayerMP.getCurrentEquippedItem();
            block.onBlockHarvested(world, x, y, z, meta, player);
            if(heldItem != null) { // 手上有物品,检查是否销毁?
                heldItem.func_150999_a(world, block, x, y, z, player);
                heldItem.getItem().onBlockStartBreak(heldItem, x, y, z, player);
                if(heldItem.stackSize == 0) {
                    manager.thisPlayerMP.destroyCurrentEquippedItem();
                }
            }
            removeBlockSuccess = block.removedByPlayer(world, player, x, y, z);
            block.onBlockDestroyedByPlayer(world, x, y, z, meta);
            if(removeBlockSuccess && block.canHarvestBlock(player, meta)) {
                harvestBlock(world, player, x, y, z, meta);
                if(heldItem != null) {
                    heldItem.getItem().onBlockDestroyed(heldItem, world, block, x, y, z, player);
                }
                block.onBlockDestroyedByPlayer(world, x, y, z, meta);
            }
        }
        // Drop experience
        if (!manager.isCreative() && removeBlockSuccess) {
            block.dropXpOnBlockBreak(world, x, y, z, block.getExpDrop(world, meta, EnchantmentHelper.getFortuneModifier(player)));
        }
    }

    /**
     * 原版harvest移植, 相同的调用触发事件, 确保不影响本身行为
     * @param worldIn
     * @param player
     * @param x
     * @param y
     * @param z
     */
    public static void harvestBlock(World worldIn, EntityPlayer player, int x, int y, int z, int meta) {
        Statue playerStatue = AllPlayerStatue.getStatue(player.getUniqueID());
        Point playerPos = new Point((int) player.posX, (int) player.posY, (int) player.posZ);
        ItemStack heldItem = player.getCurrentEquippedItem();
        Block block = worldIn.getBlock(x, y, z);
        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1); // 添加统计信息
        player.addExhaustion(0.025F);  // 添加饥饿值
        List<ItemStack> drops = new ArrayList<>();
        if (block.canSilkHarvest(worldIn, player, x, y, z, meta) && EnchantmentHelper.getSilkTouchModifier(player)) {
            // 精准采集逻辑
            ArrayList<ItemStack> items = new ArrayList<ItemStack>();
            Item blockItem = Item.getItemFromBlock(block);
            if(blockItem == null) {
                drops.addAll(playerStatue.dropsItem);
                playerStatue.dropsItem.clear();  // 用完就清理掉
            } else {
                int itemMeta = blockItem.getHasSubtypes() ? meta : 0;
                ItemStack itemstack = new ItemStack(blockItem, 1, itemMeta);
                drops.add(itemstack);
            }
        }
        else {
            // 因为方块已经被破坏了, 无法直接getDrops, 添加预先准备的缓存 =(
            drops.addAll(playerStatue.dropsItem);
            playerStatue.dropsItem.clear();  // 用完就清理掉
        }
        for(ItemStack stack : drops) {
            worldIn.spawnEntityInWorld(new EntityItem(worldIn, playerPos.x, playerPos.y, playerPos.z, stack)); // 生成掉落物逻辑
        }
    }
    // endregion
}
