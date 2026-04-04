package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 原木判定规则。
 */
public final class ChainLogRules {

    private ChainLogRules() {}

    /**
     * 判断目标是否为原木。
     *
     * @param world 当前世界
     * @param target 目标方块
     * @return 是否为原木
     */
    public static boolean isLogBlock(World world, ChainTarget target) {
        if (world == null || target == null) {
            return false;
        }

        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        int meta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return isLogBlock(world, target.getX(), target.getY(), target.getZ(), block, meta);
    }

    /**
     * 判断指定方块是否为原木。
     *
     * @param world 当前世界
     * @param x 方块 X 坐标
     * @param y 方块 Y 坐标
     * @param z 方块 Z 坐标
     * @param block 方块
     * @param meta 元数据
     * @return 是否为原木
     */
    public static boolean isLogBlock(World world, int x, int y, int z, Block block, int meta) {
        if (world == null || block == null) {
            return false;
        }

        if (block.isWood(world, x, y, z)) {
            return true;
        }

        Item item = Item.getItemFromBlock(block);
        if (item == null) {
            return false;
        }

        ItemStack stack = new ItemStack(item, 1, meta);
        int[] oreIds = OreDictionary.getOreIDs(stack);
        for (int oreId : oreIds) {
            if ("logWood".equals(OreDictionary.getOreName(oreId))) {
                return true;
            }
        }

        return false;
    }
}
