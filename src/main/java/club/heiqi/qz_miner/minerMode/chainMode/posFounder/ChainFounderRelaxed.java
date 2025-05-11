package club.heiqi.qz_miner.minerMode.chainMode.posFounder;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.List;

public class ChainFounderRelaxed extends ChainFounder {
    public List<ItemStack> sampleDrops;


    public ChainFounderRelaxed(AbstractMode mode) {
        super(mode);
        int fortune = EnchantmentHelper.getFortuneModifier(manager.player);
        sampleDrops = mode.blockSample.getDrops(manager.world, center.x, center.y, center.z, mode.blockSampleMeta, fortune);
    }

    @Override
    public void mainLogic() {
        super.mainLogic();
    }

    @Override
    public boolean filter(Vector3i pos) {
        final Block thisBlock = manager.world.getBlock(pos.x, pos.y, pos.z);
        if (thisBlock.isAir(manager.world, pos.x, pos.y, pos.z) || thisBlock.getMaterial().isLiquid()) return false;
        // 1.方块ID相同
        final int thisBID = Block.getIdFromBlock(thisBlock);
        if (thisBID == Block.getIdFromBlock(mode.blockSample)) {
            return true;
        }
        // 2.矿词相同
        ItemStack sampleStack = new ItemStack(mode.blockSample);
        ItemStack blockStack = new ItemStack(thisBlock);
        int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
        int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
        for (int sampleOreID : sampleOreIDs) {
            for (int blockOreID : blockOreIDs) {
                if (sampleOreID == blockOreID) {
                    return true;
                }
            }
        }
        // 3.掉落物相同
        int fortune = EnchantmentHelper.getFortuneModifier(manager.player);
        List<ItemStack> blockDrops = thisBlock.getDrops(manager.world, pos.x, pos.y, pos.z, manager.world.getBlockMetadata(pos.x, pos.y, pos.z), fortune);
        for (ItemStack drop : blockDrops) {
            for (ItemStack sampleDrop : sampleDrops) {
                if (drop.isItemEqual(sampleDrop)) {
                    return true;
                }
            }
        }
        return false;
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
