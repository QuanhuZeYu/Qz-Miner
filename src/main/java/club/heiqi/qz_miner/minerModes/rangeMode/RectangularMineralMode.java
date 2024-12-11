package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.breakBlock.BlockBreaker;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Rectangular;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.List;

public class RectangularMineralMode extends AbstractMode {
    public RectangularMineralMode() {
        super();
    }

    @Override
    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        positionFounder = new Rectangular(center, player);
        breaker = new BlockBreaker(player, world);
        // 收集掉落物样本
        Block block = breaker.world.getBlock(center.x, center.y, center.z);
        int meta = breaker.world.getBlockMetadata(center.x, center.y, center.z);
        int fortune = EnchantmentHelper.getFortuneModifier(player); // 获取附魔附魔等级
        dropSample = block.getDrops(world, center.x, center.y, center.z, meta, fortune);
        blockSample = block;
    }

    @Override
    public boolean filter(Vector3i pos) {
        Block block = breaker.world.getBlock(pos.x, pos.y, pos.z);
        String blockUnLocalizedName = block.getUnlocalizedName().toLowerCase();
        int meta = breaker.world.getBlockMetadata(pos.x, pos.y, pos.z);
        int fortune = EnchantmentHelper.getFortuneModifier(breaker.player);
        List<ItemStack> drop = block.getDrops(breaker.world, pos.x, pos.y, pos.z, meta, fortune);
        // 如果和样本掉落物有一样的则认为可以连锁
        for (ItemStack stack : drop) {
            for (ItemStack sample : dropSample) {
                if (stack.isItemEqual(sample)) {
                    return true;
                }
            }
        }
        String sampleUnLocalizedName = blockSample.getUnlocalizedName().toLowerCase();
        if ((blockUnLocalizedName.startsWith("ore")
            || blockUnLocalizedName.contains("blockore")
            || blockUnLocalizedName.contains("rawore")
            ) && (sampleUnLocalizedName.startsWith("ore")
            || sampleUnLocalizedName.contains("blockore")
            || sampleUnLocalizedName.contains("rawore"))) { // 如果样本和挖掘方块都为矿石，则认为可以连锁
            return true;
        }
        return blockUnLocalizedName.equals(sampleUnLocalizedName);
    }
}
