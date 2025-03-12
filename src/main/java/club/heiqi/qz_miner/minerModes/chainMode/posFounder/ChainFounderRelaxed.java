package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChainFounderRelaxed extends ChainFounder {
    public List<ItemStack> sampleDrops = new ArrayList<>();


    public ChainFounderRelaxed(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
        int fortune = EnchantmentHelper.getFortuneModifier(player);
        sampleDrops = mode.blockSample.getDrops(world, center.x, center.y, center.z, mode.blockSampleMeta, fortune);
    }

    @Override
    public void mainLogic() {
        super.mainLogic();
    }

    @Override
    public boolean filter(Vector3i pos) {
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
        // 物块类相同
        if (mode.blockSample.equals(block)) {
            return true;
        }
        // 矿词相同
        ItemStack sampleStack = new ItemStack(mode.blockSample);
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

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
