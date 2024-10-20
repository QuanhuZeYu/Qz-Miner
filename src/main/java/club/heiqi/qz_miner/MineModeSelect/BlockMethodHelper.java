package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Storage.AllPlayerStatue;
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
import net.minecraftforge.oredict.OreDictionary;


import java.util.Comparator;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Arrays;
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
                if(drop.equals(targetDrop)) return true; // 完全相同提前返回
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

    /**
     * 寻找中心点半径内与中心点相邻的点, 使用 getSurroundPointsEnhanced 作为相邻判断条件, 列表顺序为从内到外
     * @param world
     * @param center
     * @param radius
     * @return
     */
    public static List<Point> getInChainBoxPoint(World world, Point center, int radius) {
        List<Point> result = new ArrayList<>();

        // 遍历立方体范围内的所有点
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int y = center.y - radius; y <= center.y + radius; y++) {
                for (int z = center.z - radius; z <= center.z + radius; z++) {
                    // 跳过中心点本身
                    if (x == center.x && y == center.y && z == center.z) {
                        continue;
                    }

                    // 创建当前点对象
                    Point currentPoint = new Point(x, y, z);

                    // 使用 getSurroundPointsEnhanced 检查当前点是否与中心点相邻
                    List<Point> surroundPoints = getSurroundPointsEnhanced(world, center, radius);
                    if (surroundPoints.contains(currentPoint)) {
                        result.add(currentPoint);
                    }
                }
            }
        }

        // 按距离从内到外排序（曼哈顿距离）
        result.sort(Comparator.comparingInt(p -> PointMethodHelper.manhattanDistance(center, p)));

        return result;
    }

    /**
     * 寻找给出点所有的周围方块
     * @param world
     * @param pointList
     * @return
     */
    public static List<Point> getOutLine(World world, List<Point> pointList) {
        List<Point> ret = new LinkedList<>();
        for (Point curPoint : pointList) {
            if (BlockMethodHelper.checkPointBlockIsValid(world, curPoint)) {
                ret.add(curPoint);
            }
        }
        return ret;
    }

    /**
     * 处理了取人要挖掘的点, 添加了下次要访问的点, 添加了已经访问的点
     * @param world
     * @param cache
     * @param center
     * @param queue
     * @param visited
     */
    public static void getOutLine(World world, List<Point> cache, Point center, Queue<Point> queue, Set<Point> visited, int radius) {
        for(int i = 0; i < queue.size(); ++i) {
            Point curPoint = queue.poll();
            if(visited.contains(curPoint)) continue; // 避免重复访问
            visited.add(curPoint); // 添加访问记录,开始操作
            List<Point> waitAdd = BlockMethodHelper.getSurroundPoints(curPoint.x, curPoint.y, curPoint.z);
            for(Point p : waitAdd) {
                if(PointMethodHelper.calculateDistance(p, center) > radius) continue;
                if(BlockMethodHelper.checkPointBlockIsValid(world, p)) {
                    queue.add(p);
                    cache.add(p);
                }
            }
        }
    }

    public static List<Point> getSurroundPoints(int x, int y, int z) {
        Point top = new Point(x, y+1, z);
        Point bottom = new Point(x, y-1, z);
        Point left = new Point(x-1, y, z);
        Point right = new Point(x+1, y, z);
        Point front = new Point(x, y, z+1);
        Point back = new Point(x, y, z-1);
        return new ArrayList<>(Arrays.asList(top, bottom, left, right, front, back));
    }

    public static void getSurroundPoints(List<Point> pointsList, Point center) {
        Point top = center.topPoint();
        Point bottom = center.bottomPoint();
        Point left = center.xMinusPoint();
        Point right = center.xPlusPoint();
        Point front = center.zPlusPoint();
        Point back = center.zMinusPoint();
        pointsList.addAll(Arrays.asList(top, bottom, left, right, front, back));
    }

    /**
     * 寻找给出点的周围相似方块-增强模式，会根据中心点坐标和半径范围来确定
     * @param world
     * @param pointIn
     * @param radius
     * @return
     */
    public static List<Point> getSurroundPointsEnhanced(World world, Point pointIn, int radius) {
        List<Point> ret = new ArrayList<>();
        // 需要选取的范围 立方体对角
        Point TRF = new Point(pointIn.x + radius, pointIn.y + radius, pointIn.z + radius);
        Point BLB = new Point(pointIn.x - radius, pointIn.y - radius, pointIn.z - radius);
        // 遍历指定范围内的所有点
        for (int x = BLB.x; x <= TRF.x; x++) {
            for (int y = BLB.y; y <= TRF.y; y++) {
                for (int z = BLB.z; z <= TRF.z; z++) {
                    // 排除中心点本身
                    if (x == pointIn.x && y == pointIn.y && z == pointIn.z) {
                        continue;
                    }

                    // 创建当前点的对象
                    Point waitPoint = new Point(x, y, z);

                    // 检查当前点是否满足条件
                    if (checkTwoPintBlockIsSame(world, pointIn, waitPoint)) {
                        ret.add(waitPoint);
                    }
                }
            }
        }
        return ret;
    }

    public static boolean checkTwoPintBlockIsSame(World world, Point pointA, Point pointB) {
        Block blockA = world.getBlock(pointA.x, pointA.y, pointA.z);
        Block blockB = world.getBlock(pointB.x, pointB.y, pointB.z);
        if(blockA instanceof BlockOresAbstract && blockB instanceof BlockOresAbstract) {
            return true;
        }
        int[] dictA = OreDictionary.getOreIDs(new ItemStack(blockA));
        int[] dictB = OreDictionary.getOreIDs(new ItemStack(blockB));
        return checkTwoDictIsSame(dictA, dictB) || checkTwoPointBlockDropIsSame(world, pointA, pointB);
    }

    public static boolean checkTwoBlockIsSameOrSimilar(Block blockA, Block blockB) {
        if(blockA instanceof BlockOresAbstract && blockB instanceof BlockOresAbstract) {
            return true;
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

    public static boolean checkTwoPointBlockDropIsSame(World world, Point A, Point B) {
        boolean ret = false;
        Block blockA = world.getBlock(A.x, A.y, A.z);
        Block blockB = world.getBlock(B.x, B.y, B.z);
        int metaA = world.getBlockMetadata(A.x, A.y, A.z);
        int metaB = world.getBlockMetadata(B.x, B.y, B.z);
        int fortune = 10;
        List<ItemStack> blockItemA = blockA.getDrops(world, A.x, A.y, A.z, metaA, fortune);
        List<ItemStack> blockItemB = blockB.getDrops(world, B.x, B.y, B.z, metaB, fortune);
        for(ItemStack ISA : blockItemA) {
            for(ItemStack ISB : blockItemB) {
                if(ISA.isItemEqual(ISB) || Objects.equals(ISA.getUnlocalizedName(), ISB.getUnlocalizedName())) {
                    ret = true; // 判断完全相同
                    break;
                }
                if(checkTwoItemIsSimilar_IncludeCrushedOre(ISA, ISB)) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
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

    public static boolean checkTwoDictIsSame(int[] dict1, int[] dict2) {
        boolean isSame = false;
        for(int thisDict : dict1) {
            String thisDictName = OreDictionary.getOreName(thisDict);
            for(int targetDict : dict2) {
                String targetDictName = OreDictionary.getOreName(targetDict);
                if(thisDictName.equals(targetDictName)) {
                    isSame = true;
                    break;
                }
                if(thisDict == targetDict) {
                    isSame = true;
                    break;
                }
            }
        }
        return isSame;
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
    public static void tryHarvestBlock(World world, EntityPlayerMP player, Point point) {
        int x = point.x;
        int y = point.y;
        int z = point.z;
        Point playerPos = new Point((int)player.posX, (int) player.posY, (int) player.posZ);
        ItemInWorldManager manager = player.theItemInWorldManager;
        ItemStack stack = player.getCurrentEquippedItem();
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
            if(removeBlockSuccess) {
                block.onBlockDestroyedByPlayer(world, x, y, z, meta);
            }
            manager.thisPlayerMP.playerNetServerHandler.sendPacket(new S23PacketBlockChange(x, y, z, world));
        } else { // 生存逻辑
            ItemStack heldItem = manager.thisPlayerMP.getCurrentEquippedItem();
            if(heldItem != null) { // 手上有物品,检查是否销毁?
                heldItem.func_150999_a(world, block, x, y, z, player);
                if(heldItem.stackSize == 0) {
                    manager.thisPlayerMP.destroyCurrentEquippedItem();
                }
            }
            removeBlockSuccess = removeBlock(world, player, x, y, z, false);
            if(block.canHarvestBlock(player, meta)) {
                harvestBlock(world, player, x, y, z, meta);
            }
        }
        // Drop experience
        if (!manager.isCreative() && removeBlockSuccess) {
            block.dropXpOnBlockBreak(world, x, y, z, block.getExpDrop(world, meta, EnchantmentHelper.getFortuneModifier(player)));
        }
    }

    public static boolean removeBlock(World world, EntityPlayer player, int x, int y, int z, boolean canHarvest) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        block.onBlockHarvested(world, x, y, z, meta, player);
        boolean canRemoveByPlayer = block.removedByPlayer(world, player, x, y, z, canHarvest);
        if(canRemoveByPlayer) {
            block.onBlockDestroyedByPlayer(world, x, y, z, meta);
        }
        return canRemoveByPlayer;
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
        Block block = worldIn.getBlock(x, y, z);
        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1);
        player.addExhaustion(0.025F);
        List<ItemStack> drops = new ArrayList<>();
        if (block.canSilkHarvest(worldIn, player, x, y, z, meta) && EnchantmentHelper.getSilkTouchModifier(player)) {
            // 精准采集逻辑
            ArrayList<ItemStack> items = new ArrayList<ItemStack>();
            Item blockItem = Item.getItemFromBlock(block);
            int itemMeta = blockItem.getHasSubtypes() ? meta : 0;
            ItemStack itemstack = new ItemStack(blockItem, 1, itemMeta);
            drops.add(itemstack);
        }
        else {
            // 因为方块已经被破坏了, 无法直接getDrops, 添加预先准备的缓存 =(
            drops.addAll(playerStatue.dropsItem);
            playerStatue.dropsItem.clear();  // 用完就清理掉
        }
        for(ItemStack stack : drops) {
            worldIn.spawnEntityInWorld(new EntityItem(worldIn, playerPos.x, playerPos.y, playerPos.z, stack));
        }
    }
}
