package club.heiqi.qz_miner.minerModes.breaker;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.mixins.GTMixin.BWTileEntityMetaGeneratedOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.BlockBaseOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.TileEntityOresAccessor;
import club.heiqi.qz_miner.util.CalculateSightFront;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.TileEntityOres;
import gtPlusPlus.core.block.base.BlockBaseOre;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import org.jetbrains.annotations.Nullable;
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
    @Nullable
    public EntityPlayerMP player;
    public World world;
    public List<ItemStack> drops = new ArrayList<>();

    public BlockBreaker(EntityPlayer player, World world) {
        if (player instanceof EntityPlayerMP playerMP) this.player = playerMP;
        this.world = world;
    }

    public void tryHarvestBlock(Vector3i pos) {
        if (player == null) return;
        int x = pos.x; int y = pos.y; int z = pos.z;
        BlockEvent.BreakEvent breakEvent = ForgeHooks.onBlockBreakEvent(world, player.theItemInWorldManager.getGameType(), player, x, y, z);
        if (breakEvent.isCanceled()) {
            return;
        }
        // 2. 工具预检查逻辑优化
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null && heldItem.getItem().onBlockStartBreak(heldItem, x, y, z, player)) {
            return;
        }
        // 3. 元数据缓存减少重复调用
        final Block block = world.getBlock(x, y, z);
        final int metadata = world.getBlockMetadata(x, y, z);
        final int blockId = Block.getIdFromBlock(block);
        // 4. 播放音效（使用常量代替魔法数字）
        final int BLOCK_BREAK_SFX_ID = 2001;
        world.playAuxSFXAtEntity(player, BLOCK_BREAK_SFX_ID, x, y, z, blockId + (metadata << 12));
        // 5. 破坏逻辑重构
        boolean isBlockRemoved;
        if (player.capabilities.isCreativeMode) {
            isBlockRemoved = removeBlock(x, y, z, false);
            player.playerNetServerHandler.sendPacket(new S23PacketBlockChange(x, y, z, world));  // 封装数据包发送
        } else {
            isBlockRemoved = handleSurvivalBreak(x, y, z, block, metadata, heldItem);
        }
        // 6. 经验掉落逻辑优化
        if (!player.capabilities.isCreativeMode && isBlockRemoved) {
            block.dropXpOnBlockBreak(world, (int) player.posX, (int) player.posY, (int) player.posZ, breakEvent.getExpToDrop());
        }
    }

    private boolean handleSurvivalBreak(int x, int y, int z, Block block, int metadata, ItemStack tool) {
        boolean canHarvest = block.canHarvestBlock(player, metadata);
        boolean isRemoved = removeBlock(x, y, z, canHarvest);
        if (tool != null) {
            tool.func_150999_a(world, block, x, y, z, player);  // func_150999_a = onBlockDestroyed
            if (tool.stackSize <= 0) {
                player.destroyCurrentEquippedItem();
            }
        }
        if (isRemoved && canHarvest) {
            block.harvestBlock(world, player, x, y, z, metadata);
        }
        return isRemoved;
    }

    /**
     * 尝试移除指定坐标处的方块。
     *
     * @param x           方块的x坐标
     * @param y           方块的y坐标
     * @param z           方块的z坐标
     * @param canHarvest  是否允许收获方块的掉落物
     * @return 如果方块成功被移除则返回true，否则返回false
     */
    public boolean removeBlock(int x, int y, int z, boolean canHarvest) {
        new PlayerInteractEvent(player, PlayerInteractEvent.Action.LEFT_CLICK_BLOCK, x, y, z, ForgeDirection.UP.flag, world);
        canHarvest = false;
        // 获取目标位置的方块及其元数据
        Block targetBlock = world.getBlock(x, y, z);
        int blockMetadata = world.getBlockMetadata(x, y, z);
        // 通知方块即将被破坏（前置处理，如触发事件）
        targetBlock.onBlockHarvested(world, x, y, z, blockMetadata, player);
        // 尝试通过玩家移除方块，返回操作是否成功
        boolean isBlockRemoved = targetBlock.removedByPlayer(
            world, player, x, y, z, canHarvest
        );
        // 若移除成功，触发方块的销毁后处理逻辑
        if (isBlockRemoved) {
            targetBlock.onBlockDestroyedByPlayer(world, x, y, z, blockMetadata);
        }
        return isBlockRemoved;
    }

    public void handleCreativeMode(Vector3i pos, Block block, int meta) {
        if (!Block.isEqualTo(block, Blocks.skull)) {
            block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player);
        }

        if (block.removedByPlayer(world, player, pos.x, pos.y, pos.z, false)) {
            BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(
                pos.x, pos.y, pos.z, world, block, meta,
                EnchantmentHelper.getFortuneModifier(player), 1.0f, new ArrayList<>(drops), player, false
            );
            MinecraftForge.EVENT_BUS.post(event);
        }
    }

    public void handleSurvivalMode(Vector3i pos, Block block, int meta) {
        TileEntity tileEntity = world.getTileEntity(pos.x, pos.y, pos.z);
        ItemStack holdItem = player.getCurrentEquippedItem();

        if (!Block.isEqualTo(block, Blocks.skull)) {
            block.onBlockHarvested(world, pos.x, pos.y, pos.z, meta, player);
        }

        if (holdItem != null && holdItem.getItem() != null) {
            // holdItem.func_150999_a(world, block, pos.x, pos.y, pos.z, player); // 拆解出来为以下步骤
            holdItem.getItem().onBlockStartBreak(holdItem, pos.x, pos.y, pos.z, player);
            if (holdItem.getItem().onBlockDestroyed(holdItem, world, block, pos.x, pos.y, pos.z, player)) {
                player.addStat(StatList.objectUseStats[Item.getIdFromItem(holdItem.getItem())], 1);
            }
            if (holdItem.stackSize == 0) {
                player.destroyCurrentEquippedItem();
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
        // 计算掉落位置
        Vector3f dropPos = CalculateSightFront.calculatePos(player);

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
            if (CheckCompatibility.is270Upper && tileEntity instanceof TileEntityOres) {
                drop = ((TileEntityOres) tileEntity).getDrops(block, fortune);
            } else {
                drop = block.getDrops(world, pos.x, pos.y, pos.z, meta, fortune);
            }

            BlockEvent.HarvestDropsEvent event = new BlockEvent.HarvestDropsEvent(
                pos.x, pos.y, pos.z, world, block, meta, fortune, 1.0f, new ArrayList<>(drop), player, false
            );
            MinecraftForge.EVENT_BUS.post(event); // 发送收获方块事件
            drop = event.drops;

            world.setBlockToAir(pos.x, pos.y, pos.z);
//            drops.addAll(drop);
            checkFoodLevel();
        }
        for (ItemStack drop : drops) {
            world.spawnEntityInWorld(new EntityItem(world, dropPos.x, dropPos.y, dropPos.z, drop));
        }
        block.dropXpOnBlockBreak(world, pos.x, pos.y, pos.z, block.getExpDrop(world, meta, fortune));
        drops.clear();
    }

    private void gtOreHarvestBlockBefore(TileEntity tileEntity, Block block, EntityPlayer player) {
        if (!CheckCompatibility.is270Upper){
            return;
        }
        tryCatch(() -> {
            if (tileEntity instanceof TileEntityOres tileEntityOres) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    TileEntityOresAccessor.setShouldSilkTouch(true);
                    return;
                }
                TileEntityOresAccessor.setShouldFortune(true);
            }
        });

        tryCatch(() -> {
            if (tileEntity instanceof BWTileEntityMetaGeneratedOre bwTileEntityMetaGeneratedOre) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    BWTileEntityMetaGeneratedOreAccessor.setShouldSilkTouch(true);
                    return;
                }
                BWTileEntityMetaGeneratedOreAccessor.setShouldFortune(true);
            }
        });

        tryCatch(() -> {
            if (block instanceof BlockBaseOre) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    BlockBaseOreAccessor.setShouldSilkTouch(true);
                    return;
                }
                BlockBaseOreAccessor.setShouldFortune(true);
            }
        });
    }

    private void gtOreHarvestBlockAfter(TileEntity tileEntity, Block block) {
        if (!CheckCompatibility.is270Upper){
            return;
        }

        tryCatch(() -> {
            if (tileEntity instanceof TileEntityOres tileEntityOres) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    TileEntityOresAccessor.setShouldSilkTouch(false);
                    return;
                }
                TileEntityOresAccessor.setShouldFortune(false);
                if (Config.forceNatural)
                    tileEntityOres.mNatural = false;
            }
        });

        tryCatch(() -> {
            if (tileEntity instanceof BWTileEntityMetaGeneratedOre bwTileEntityMetaGeneratedOre) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    BWTileEntityMetaGeneratedOreAccessor.setShouldSilkTouch(false);
                    return;
                }
                BWTileEntityMetaGeneratedOreAccessor.setShouldFortune(false);
                if (Config.forceNatural)
                    bwTileEntityMetaGeneratedOre.mNatural = false;
            }
        });

        tryCatch(() -> {
            if (block instanceof BlockBaseOre) {
                if (EnchantmentHelper.getSilkTouchModifier(player)) {
                    BlockBaseOreAccessor.setShouldSilkTouch(false);
                    return;
                }
                BlockBaseOreAccessor.setShouldFortune(false);
            }
        });
    }

    public void tryCatch(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            MY_LOG.LOG.warn(e.toString());
        }
    }

    public void checkFoodLevel() {
        if (player.getFoodStats().getFoodLevel() <= 3) {
            player.setHealth(Math.max(0, player.getHealth() - Config.overMiningDamage));
        }
    }
}
