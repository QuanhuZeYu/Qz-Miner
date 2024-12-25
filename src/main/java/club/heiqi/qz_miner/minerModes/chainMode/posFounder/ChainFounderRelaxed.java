package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChainFounderRelaxed extends ChainFounder{
    public List<ItemStack> sampleDrops = new ArrayList<>();

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     * @param lock
     */
    public ChainFounderRelaxed(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        super(center, player, lock);
        int metaData = world.getBlockMetadata(center.x, center.y, center.z);
        int fortune = EnchantmentHelper.getFortuneModifier(player);
        sampleDrops = sampleBlock.getDrops(world, center.x, center.y, center.z, metaData, fortune);
    }

    @Override
    public boolean filter(Block block, Vector3i pos) {
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
        // 完全相同
        if (sampleBlock.equals(block)) {
            return true;
        }
        // 矿词相同
        ItemStack sampleStack = new ItemStack(sampleBlock);
        ItemStack blockStack = new ItemStack(block);
        int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
        int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
        for (int sampleOreID : sampleOreIDs) {
            for (int blockOreID : blockOreIDs) {
                if (sampleOreID == blockOreID) {
                    return true;
                }
            }
        }
        // 掉落物相同
        int fortune = EnchantmentHelper.getFortuneModifier(player);
        List<ItemStack> blockDrops = block.getDrops(world, pos.x, pos.y, pos.z, world.getBlockMetadata(pos.x, pos.y, pos.z), fortune);
        for (ItemStack drop : blockDrops) {
            for (ItemStack sampleDrop : sampleDrops) {
                if (drop.isItemEqual(sampleDrop)) {
                    return true;
                }
            }
        }
        return false;
    }
}
