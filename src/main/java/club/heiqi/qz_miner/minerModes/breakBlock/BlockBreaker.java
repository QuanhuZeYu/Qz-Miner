package club.heiqi.qz_miner.minerModes.breakBlock;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.mixins.BWTileEntityMetaGeneratedOreAccessor;
import club.heiqi.qz_miner.mixins.BlockBaseOreAccessor;
import club.heiqi.qz_miner.mixins.TileEntityOresAccessor;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.TileEntityOres;
import gtPlusPlus.core.block.base.BlockBaseOre;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import org.joml.*;

import java.lang.Math;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.minecraft.block.Block.getIdFromBlock;

/**
 * 采掘者类，存储了当前挖掘的世界和进行挖掘的玩家<br>
 * 仅限服务端运行!
 */
public class BlockBreaker {
    public static float dropDistance = 2.0f;
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
        world.playAuxSFXAtEntity(player, 2001, pos.x, pos.y, pos.z, getIdFromBlock(block) + (meta << 12)); // 播放方块破坏音效

        if (itemInWorldManager.isCreative()) { // 创造模式
            handleCreativeMode(pos, block, meta);
        } else { // 非创造模式
            handleSurvivalMode(pos, block, meta);
        }
    }

    public void handleCreativeMode(Vector3i pos, Block block, int meta) {
        ItemInWorldManager itemInWorldManager = player.theItemInWorldManager;
        block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player);

        if (block.removedByPlayer(world, player, pos.x, pos.y, pos.z, false)) {
            BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(
                pos.x, pos.y, pos.z, world, block, meta,
                EnchantmentHelper.getFortuneModifier(player), 1.0f, new ArrayList<>(drops), player, false
            );
            MinecraftForge.EVENT_BUS.post(event);
        }

        // 发送方块更新包
        itemInWorldManager.thisPlayerMP.playerNetServerHandler.sendPacket(
            new S23PacketBlockChange(pos.x, pos.y, pos.z, world)
        );
    }

    public void handleSurvivalMode(Vector3i pos, Block block, int meta) {
        TileEntity tileEntity = world.getTileEntity(pos.x, pos.y, pos.z);
        ItemInWorldManager itemInWorldManager = player.theItemInWorldManager;
        ItemStack holdItem = itemInWorldManager.thisPlayerMP.getCurrentEquippedItem();
        block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player);

        if (holdItem != null && holdItem.getItem() != null) {
            // holdItem.func_150999_a(world, block, pos.x, pos.y, pos.z, player); // 拆解出来为以下步骤
            holdItem.getItem().onBlockStartBreak(holdItem, pos.x, pos.y, pos.z, player);
            if (holdItem.getItem().onBlockDestroyed(holdItem, world, block, pos.x, pos.y, pos.z, player)) {
                player.addStat(StatList.objectUseStats[Item.getIdFromItem(holdItem.getItem())], 1);
            }

            if (holdItem.stackSize == 0) {
                itemInWorldManager.thisPlayerMP.destroyCurrentEquippedItem();
            }
        }

        if (block.canHarvestBlock(player, meta)) {

            block.onBlockDestroyedByPlayer(world, pos.x, pos.y, pos.z, meta);
            gtOreHarvestBlockBefore(tileEntity, block, player);
            harvestBlock(pos, meta);
            gtOreHarvestBlockAfter(tileEntity, block);
        }
    }

    public void harvestBlock(Vector3i pos, int meta) {
        int fortune = EnchantmentHelper.getFortuneModifier(player); // 获取附魔附魔等级
        // 计算掉落物落点
        Vector3f playerPos = new Vector3f((float) player.posX, (float) player.posY + player.eyeHeight, (float) player.posZ);
        // 计算玩家视线方向
        float pitch = (float) Math.toRadians(player.rotationPitch);
        float yaw = (float) Math.toRadians(player.rotationYaw + 90);

        float vx = (float) (Math.cos(pitch) * Math.cos(yaw));
        float vy = (float) -Math.sin(pitch);
        float vz = (float) (Math.cos(pitch) * Math.sin(yaw));
        // 视线方向的单位向量
        Vector3f direction = new Vector3f(vx, vy, vz).normalize();
        Vector3f dropPos = new Vector3f(direction).mul(dropDistance).add(playerPos);

        Block block = world.getBlock(pos.x, pos.y, pos.z);
        TileEntity tileEntity = world.getTileEntity(pos.x, pos.y, pos.z);

        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1);
        player.addExhaustion(Config.hunger);

        if (block.canSilkHarvest(world, player, pos.x, pos.y, pos.z, world.getBlockMetadata(pos.x, pos.y, pos.z))
            && EnchantmentHelper.getSilkTouchModifier(player)) { // 判断是否可以进行精准采集
            Item blockItem = Item.getItemFromBlock(block);
            if (blockItem != null) {
                int itemMeta = blockItem.getHasSubtypes() ? meta : 0;
                ItemStack itemStack = new ItemStack(blockItem, 1, itemMeta);
                BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(
                    pos.x, pos.y, pos.z, world, block, meta, fortune, 1.0f, new ArrayList<>(Arrays.asList(itemStack)), player, true);
                MinecraftForge.EVENT_BUS.post(event); // 发送收获方块事件
                if (block.removedByPlayer(world, player, pos.x, pos.y, pos.z)) {
                    drops.addAll(event.drops);
                    checkFoodLevel();
                }
            }
        } else { // 否则进行普通采集
            // 后续可在此处添加事件触发功能
            ArrayList<ItemStack> drop;
            if (tileEntity instanceof TileEntityOres) {
                drop = ((TileEntityOres) tileEntity).getDrops(block, fortune);
            } else {
                drop = block.getDrops(world, pos.x, pos.y, pos.z, meta, fortune);
            }
//            drop.forEach(itemStack -> MY_LOG.LOG.info("未修改前: 掉落物x{}个: {}", itemStack.stackSize, itemStack.getDisplayName()));

            BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(
                pos.x, pos.y, pos.z, world, block, meta, fortune, 1.0f, new ArrayList<>(drop), player, false
            );
            MinecraftForge.EVENT_BUS.post(event); // 发送收获方块事件
            drop = event.drops;
            /*drop.forEach(itemStack -> {
                MY_LOG.LOG.info("当前时运: {}, 掉落物x{}个: {}", fortune, itemStack.stackSize, itemStack.getDisplayName());
            });*/

            if (block.removedByPlayer(world, player, pos.x, pos.y, pos.z, true)) { // 移除方块事件
                drops.addAll(drop);
                checkFoodLevel();
            }
        }
        for (ItemStack drop : drops) {
            world.spawnEntityInWorld(new EntityItem(world, dropPos.x, dropPos.y, dropPos.z, drop));
        }
        block.dropXpOnBlockBreak(world, pos.x, pos.y, pos.z, block.getExpDrop(world, meta, fortune));
        drops.clear();
    }

    private void gtOreHarvestBlockBefore(TileEntity tileEntity, Block block, EntityPlayer player) {
        if (!CheckCompatibility.hasAll){
            return;
        }

        if (tileEntity instanceof TileEntityOres tileEntityOres) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                TileEntityOresAccessor.setShouldSilkTouch(true);
                return;
            }
            TileEntityOresAccessor.setShouldFortune(true);
        }

        if (tileEntity instanceof BWTileEntityMetaGeneratedOre bwTileEntityMetaGeneratedOre) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                BWTileEntityMetaGeneratedOreAccessor.setShouldSilkTouch(true);
                return;
            }
            BWTileEntityMetaGeneratedOreAccessor.setShouldFortune(true);
        }

        if (block instanceof BlockBaseOre) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                BlockBaseOreAccessor.setShouldSilkTouch(true);
                return;
            }
            BlockBaseOreAccessor.setShouldFortune(true);
        }
    }

    private void gtOreHarvestBlockAfter(TileEntity tileEntity, Block block) {
        if (!CheckCompatibility.hasAll){
            return;
        }

        if (tileEntity instanceof TileEntityOres tileEntityOres) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                TileEntityOresAccessor.setShouldSilkTouch(false);
                return;
            }
            TileEntityOresAccessor.setShouldFortune(false);
            if (Config.forceNatural)
                tileEntityOres.mNatural = false;
        }

        if (tileEntity instanceof BWTileEntityMetaGeneratedOre bwTileEntityMetaGeneratedOre) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                BWTileEntityMetaGeneratedOreAccessor.setShouldSilkTouch(false);
                return;
            }
            BWTileEntityMetaGeneratedOreAccessor.setShouldFortune(false);
            if (Config.forceNatural)
                bwTileEntityMetaGeneratedOre.mNatural = false;
        }

        if (block instanceof BlockBaseOre) {
            if (EnchantmentHelper.getSilkTouchModifier(player)) {
                BlockBaseOreAccessor.setShouldSilkTouch(false);
                return;
            }
            BlockBaseOreAccessor.setShouldFortune(false);
        }
    }

    public void checkFoodLevel() {
        if (player.getFoodStats().getFoodLevel() <= 3) {
            player.setHealth(Math.max(0, player.getHealth() - Config.overMiningDamage));
        }
    }
}
