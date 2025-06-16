package club.heiqi.qz_miner.minerMode.chainMode.posFounder;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.AsyncManager;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

public class ChainFounderThreadRelaxed extends ChainFounderThread {
    public List<ItemStack> sampleDrops;


    public ChainFounderThreadRelaxed(AbstractMode mode) {
        super(mode);
        int fortune = EnchantmentHelper.getFortuneModifier(manager.player);
        World world = manager.player.worldObj;
        sampleDrops = mode.blockSample.getDrops(world, center.x, center.y, center.z, mode.blockSampleMeta, fortune);
    }

    @Override
    public void mainLogic() {
        super.mainLogic();
    }

    @Override
    public Vector3i filter(Vector3i pos) {
        /*RunnableFuture<Vector3i> future = new FutureTask<>(() -> {*/
        try {
            World world = manager.player.worldObj;
            final Block thisBlock = world.getBlock(pos.x, pos.y, pos.z);
            if (thisBlock.isAir(world, pos.x, pos.y, pos.z) || thisBlock.getMaterial().isLiquid()) return null;
            // 1.方块ID相同
            final int thisBID = Block.getIdFromBlock(thisBlock);
            if (thisBID == Block.getIdFromBlock(mode.blockSample)) {
                return pos;
            }
            // 2.矿词相同
            ItemStack sampleStack = new ItemStack(mode.blockSample);
            ItemStack blockStack = new ItemStack(thisBlock);
            int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
            int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
            for (int sampleOreID : sampleOreIDs) {
                for (int blockOreID : blockOreIDs) {
                    if (sampleOreID == blockOreID) {
                        return pos;
                    }
                }
            }
            // 3.掉落物相同
            int fortune = EnchantmentHelper.getFortuneModifier(manager.player);
            List<ItemStack> blockDrops = thisBlock.getDrops(world, pos.x, pos.y, pos.z, world.getBlockMetadata(pos.x, pos.y, pos.z), fortune);
            for (ItemStack drop : blockDrops) {
                for (ItemStack sampleDrop : sampleDrops) {
                    if (drop.isItemEqual(sampleDrop)) {
                        return pos;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            doWaitBool();
        }
        /*});
        AsyncManager.pollTask(future);
        return future;*/
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
