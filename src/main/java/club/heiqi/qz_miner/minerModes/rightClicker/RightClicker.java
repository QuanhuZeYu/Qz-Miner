package club.heiqi.qz_miner.minerModes.rightClicker;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

public class RightClicker {

    public EntityPlayer player;
    public World world;

    public RightClicker(EntityPlayer player, World world) {
        this.player = player; this.world = world;
    }

    public List<ItemStack> captureDrops = new ArrayList<>();
    public void rightClick(Vector3i pos) {
        int x = pos.x; int y = pos.y; int z = pos.z;
        Block block = world.getBlock(x,y,z);
        int meta = world.getBlockMetadata(x,y,z);
        int fortune = EnchantmentHelper.getFortuneModifier(player);
        if (block == Blocks.air) return;
        // 农作物右键处理逻辑
        if (block instanceof BlockBush blockBush) {
            if (meta >= 7) {
                ArrayList<ItemStack> drops = blockBush.getDrops(world, x, y, z, meta, fortune);
                world.setBlockMetadataWithNotify(x, y, z, 0, 2);
                if (Config.dropItemToSelf) {
                    // 掉落到自身的逻辑
                    if (captureDrops.isEmpty()) captureDrops.addAll(drops);
                    for (ItemStack captureDrop : captureDrops) {
                        for (ItemStack drop : new ArrayList<>(drops)) {
                            if (Utils.areStacksMergeable(captureDrop, drop)) {
                                captureDrop.stackSize += drop.stackSize;
                                drops.remove(drop);
                            }
                        }
                    }
                    if (!drops.isEmpty()) captureDrops.addAll(drops);
                }
                else {
                    // 直接掉落
                    drops.forEach(drop -> {
                        EntityItem entityItem = new EntityItem(world, pos.x, pos.y + 0.5, pos.z, drop);
                        entityItem.delayBeforeCanPickup = 5;
                        world.spawnEntityInWorld(entityItem);
                    });
                }
            }
        }
        else {
            block.onBlockActivated(world,x,y,z,player,1,0,0,0);
            block.onBlockClicked(world,x,y,z,player);
        }
    }

    public void dropCapture() {
        Vector3f pos = Utils.getItemDropPos(player);
        for (ItemStack captureDrop : captureDrops) {
            EntityItem entityItem = new EntityItem(world, pos.x, pos.y, pos.z, captureDrop);
            entityItem.delayBeforeCanPickup = 5;
            world.spawnEntityInWorld(entityItem);
        }
        captureDrops.clear();
    }
}
