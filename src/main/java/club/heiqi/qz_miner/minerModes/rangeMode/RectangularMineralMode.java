package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Rectangular;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.BlockOresAbstract;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

public class RectangularMineralMode extends AbstractMode {
    public RectangularMineralMode() {
        super();
    }

    @Override
    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        super.setup(world, player, center);
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
        String sampleUnLocalizedName = blockSample.getUnlocalizedName().toLowerCase();
        ItemStack sampleStack = new ItemStack(blockSample);
        ItemStack blockStack = new ItemStack(block);
        int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
        int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
        boolean is270Upper = CheckCompatibility.isHasClass_BlockOresAbstract;
        // 粗矿逻辑直接通过
        if (is270Upper) {
            if (block instanceof BlockOresAbstract) {
                return true;
            }
        }
        // 连锁逻辑
        for (int sampleOreID : sampleOreIDs) {
            for (int blockOreID : blockOreIDs) {
                if (sampleOreID == blockOreID) {
                    return true;
                }
            }
        }
        // 下面这段逻辑即将废弃
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
