package club.heiqi.qz_miner.minerModes.chainMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Strict;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.World;
import org.joml.Vector3i;

public class StrictChainMode extends AbstractMode {
    @Override
    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        super.setup(world, player, center);
        blockSample = world.getBlock(center.x, center.y, center.z);
        ChainFounder_Strict founder = ((ChainFounder_Strict) positionFounder);
        if (founder != null) {
            founder.sampleBlock = blockSample;
            founder.itemBlockMeta = world.getBlockMetadata(center.x, center.y, center.z);
        }
    }

    @Override
    public boolean checkCanBreak(Vector3i pos) {
        World world = breaker.world;
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        int meta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        EntityPlayerMP player = breaker.player;
        ItemInWorldManager iwm = player.theItemInWorldManager;
        ItemStack holdItem = iwm.thisPlayerMP.getCurrentEquippedItem();
        // 判断是否为创造模式
        if (iwm.getGameType().isCreative()) {
            return true;
        }
        // 判断是否为空气
        if (block == Blocks.air) {
            return false;
        }
        // 判断是否为流体
        if (block.getMaterial().isLiquid()) {
            return false;
        }
        // 判断是否为基岩
        if (block == Blocks.bedrock) {
            return false;
        }
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        return true;
    }
}
