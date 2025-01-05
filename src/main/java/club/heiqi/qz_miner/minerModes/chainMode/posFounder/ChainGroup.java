package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChainGroup extends ChainFounder_Strict{
    public int sampleBlockMeta;
    public ItemStack sampleBlockItem;
    public int sampleBlockItemMeta;
    public String sampleBlockItemUnLocalizedName;

    public List<ItemStack> sameList = new ArrayList<>();
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player 破坏者
     * @param lock 锁
     */
    public ChainGroup(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        super(center, player, lock);
        sampleBlockMeta = world.getBlockMetadata(center.x, center.y, center.z);
        sampleBlockItem = new ItemStack(sampleBlock, 1, sampleBlockMeta);
        sampleBlockItemMeta = sampleBlockItem.getItemDamage();
        sampleBlockItemUnLocalizedName = sampleBlockItem.getUnlocalizedName();
        sameList = Config.getChainGroup();
//        MY_LOG.LOG.info("sameList: {}", sameList);
    }

    @Override
    public boolean filter(Block block, Vector3i pos) {
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
        int blockMeta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        ItemStack blockItem = new ItemStack(block, 1, blockMeta);
        for (ItemStack itemStack : sameList) {
//            MY_LOG.LOG.info("对比项: {} - {}, {} - {}", itemStack.getUnlocalizedName(), itemStack.getItemDamage(), blockItem.getUnlocalizedName(), blockItem.getItemDamage());
            if (itemStack.isItemEqual(blockItem)) {
                return true;
            }
        }
        return false;
    }
}
