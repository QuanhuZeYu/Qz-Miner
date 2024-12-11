package club.heiqi.qz_miner.minerModes.breakBlock;

import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.stats.StatList;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import org.joml.*;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.block.Block.getIdFromBlock;

/**
 * 采掘者类，存储了当前挖掘的世界和进行挖掘的玩家
 */
public class BlockBreaker {
    public EntityPlayerMP player;
    public World world;
    public List<ItemStack> drops = new ArrayList<>();

    public BlockBreaker(EntityPlayerMP player, World world) {
        this.player = player;
        this.world = world;
    }

    public void tryHarvestBlock(Vector3i pos) {
        ItemInWorldManager itemInWorldManager = player.theItemInWorldManager;
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        int meta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        int fortune = EnchantmentHelper.getFortuneModifier(player); // 获取附魔附魔等级

        itemInWorldManager.theWorld.playAuxSFXAtEntity(player, 2001, pos.x, pos.y, pos.z, getIdFromBlock(block) + (meta << 12)); // 播放方块破坏音效
        List<ItemStack> drop = block.getDrops(world, pos.x, pos.y, pos.z, meta, fortune);
//        drops.addAll(drop);

        boolean removeSuccess = false; // 是否成功移除方块
        if (itemInWorldManager.isCreative()) { // 创造模式
            block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player); // 触发方块的方块破坏事件
            removeSuccess = block.removedByPlayer(world, player, pos.x, pos.y, pos.z, false);
            if (removeSuccess) {
                BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(pos.x, pos.y, pos.z, world, block, meta,
                    EnchantmentHelper.getFortuneModifier(player), 1.0f, new ArrayList<>(drop), player, false);
                MinecraftForge.EVENT_BUS.post(event); // 发送收获方块事件
            }
            itemInWorldManager.thisPlayerMP.playerNetServerHandler.sendPacket(new S23PacketBlockChange(pos.x, pos.y, pos.z, world)); // 发送方块更新包
        } else { // 非创造模式
            ItemStack holdItem = itemInWorldManager.thisPlayerMP.getCurrentEquippedItem();
            block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player); // 触发方块的方块收获事件
            if (holdItem != null) {
                holdItem.func_150999_a(world, block, pos.x, pos.y, pos.z, player);
                holdItem.getItem().onBlockStartBreak(holdItem, pos.x, pos.y, pos.z, player);
                if(holdItem.stackSize == 0) {
                    itemInWorldManager.thisPlayerMP.destroyCurrentEquippedItem();
                }
            }
            // removeSuccess = block.removedByPlayer(world, player, pos.x, pos.y, pos.z); // 移除方块事件
            if (block.canHarvestBlock(player, meta)) {
                harvestBlock(pos, meta);
                if (holdItem != null) {
                    holdItem.getItem().onBlockDestroyed(holdItem, world, block, pos.x, pos.y, pos.z, player);
                }
                block.onBlockDestroyedByPlayer(world, pos.x, pos.y, pos.z, meta);
            }
        }
        if (!itemInWorldManager.isCreative() && removeSuccess) {
            block.dropXpOnBlockBreak(world, pos.x, pos.y, pos.z, block.getExpDrop(world, meta, fortune));
        }
    }

    public void harvestBlock(Vector3i pos, int meta) {
        int fortune = EnchantmentHelper.getFortuneModifier(player); // 获取附魔附魔等级
        // 计算掉落物落点
        Vector3d playerPos = new Vector3d(player.posX, player.posY, player.posZ);
        Vector4f zForward = new Vector4f(0, 0, -1, 0);
        float pitch = player.rotationPitch;
        float yaw = player.rotationYaw;
        Matrix4f rotationMatrix = new Matrix4f();
        rotationMatrix.rotateY(yaw);
        rotationMatrix.rotateX(pitch);
        rotationMatrix.transform(zForward);
        Vector3f direction = new Vector3f(zForward.x, zForward.y, zForward.z);
        Vector3d dropPos = playerPos.add(new Vector3d(direction.x * 0.5, direction.y * 0.5, direction.z * 0.5));

        Block block = world.getBlock(pos.x, pos.y, pos.z);
        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1);
        player.addExhaustion(0.025f);
        if (block.canSilkHarvest(world, player, pos.x, pos.y, pos.z, world.getBlockMetadata(pos.x, pos.y, pos.z))
            && EnchantmentHelper.getSilkTouchModifier(player)) { // 判断是否可以进行精准采集
            Item blockItem = Item.getItemFromBlock(block);
            if (blockItem != null) {
                int itemMeta = blockItem.getHasSubtypes() ? meta : 0;
                ItemStack itemStack = new ItemStack(blockItem, 1, itemMeta);
                drops.add(itemStack);
            }
        } else { // 否则进行普通采集
            // 后续可在此处添加事件触发功能
            if (block.removedByPlayer(world, player, pos.x, pos.y, pos.z)) { // 移除方块事件
                List<ItemStack> drop = block.getDrops(world, pos.x, pos.y, pos.z, meta, fortune);
                drops.addAll(drop);
            }
        }
        for (ItemStack drop : drops) {
            world.spawnEntityInWorld(new EntityItem(world, dropPos.x, dropPos.y, dropPos.z, drop));
        }
        drops.clear();
    }
}
