package club.heiqi.qz_miner.minerMode.breaker;

import appeng.tile.AEBaseTile;
import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerMode.utils.Utils;
import club.heiqi.qz_miner.mixins.GTMixin.BWTileEntityMetaGeneratedOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.BlockBaseOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.TileEntityOresAccessor;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.TileEntityOres;
import gtPlusPlus.core.block.base.BlockBaseOre;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * 采掘者类，存储了当前挖掘的世界和进行挖掘的玩家<br>
 * 仅限服务端运行!
 */
public class BlockBreaker {
    public Logger LOG = LogManager.getLogger();
    public EntityPlayer player;
    public World world;

    public BlockBreaker(EntityPlayer player, World world) {
        this.player = player;
        this.world = world;
    }

    public void tryHarvestBlock(Vector3i pos) {
        if (player == null || player.worldObj.isRemote) return;
        int x = pos.x; int y = pos.y; int z = pos.z;
        // 1. 元数据缓存减少重复调用
        final Block block = world.getBlock(x, y, z);
        final int metadata = world.getBlockMetadata(x, y, z);
        final int blockId = Block.getIdFromBlock(block);
        final TileEntity te = world.getTileEntity(x,y,z);
        /*player.theItemInWorldManager.tryHarvestBlock(x,y,z);*/
        // 2. 处理AE逻辑
        if (CheckCompatibility.isHasClass_AE2 && te instanceof AEBaseTile aeTe) {
            List<ItemStack> drops = new ArrayList<>();
            aeTe.getDrops(world,x,y,z,drops);
            Vector3f dropPos = Utils.getItemDropPos(player);
            drops.forEach(d -> world.spawnEntityInWorld(new EntityItem(world,dropPos.x,dropPos.y,dropPos.z,d)));
            return;
        }
        copyTryHarvestBlock(pos);
        /*BlockEvent.BreakEvent breakEvent = ForgeHooks.onBlockBreakEvent(world, selectType(), player, x, y, z);
        if (breakEvent.isCanceled()) {
            return false;
        }
        // 3. 工具预检查逻辑优化
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null && heldItem.getItem().onBlockStartBreak(heldItem, x, y, z, player)) {
            // 事件被 onBlockStartBreak 阻止
            return false;
        }
        // 4. 播放音效（使用常量代替魔法数字）
        final int BLOCK_BREAK_SFX_ID = 2001;
        world.playAuxSFXAtEntity(player, BLOCK_BREAK_SFX_ID, x, y, z, blockId + (metadata << 12));
        // 5. 破坏逻辑重构
        boolean isBlockRemoved;
        if (player.capabilities.isCreativeMode) {
            isBlockRemoved = removeBlock(x, y, z, true);
            player.playerNetServerHandler.sendPacket(new S23PacketBlockChange(x, y, z, world));  // 封装数据包发送
        } else {
            isBlockRemoved = handleSurvivalBreak(x, y, z, block, metadata, heldItem);
        }
        // 6. 经验掉落逻辑优化
        if (!player.capabilities.isCreativeMode && isBlockRemoved) {
            block.dropXpOnBlockBreak(world, (int) player.posX, (int) player.posY, (int) player.posZ, breakEvent.getExpToDrop());
        }
        return isBlockRemoved;*/
    }

    public void copyTryHarvestBlock(Vector3i pos) {
        int x = pos.x;int y = pos.y;int z = pos.z;
        WorldSettings.GameType gameType = WorldSettings.GameType.SURVIVAL; // 默认为生存模式
        if (player instanceof EntityPlayerMP playerMP) {
            if (playerMP.theItemInWorldManager.isCreative()) {
                gameType = WorldSettings.GameType.CREATIVE;
            }
            BlockEvent.BreakEvent event = copyOnBlockBreakEvent(gameType,pos);
            if (event.isCanceled()) {
                return;
            }
        }
        ItemStack stack = player.getCurrentEquippedItem();
        if (stack != null && stack.getItem().onBlockStartBreak(stack,x,y,z,player)) {
            return;
        }
        Block block = world.getBlock(x,y,z);
        int meta = world.getBlockMetadata(x,y,z);
        if (gameType.isCreative()) {
            // 移除方块
            block.onBlockHarvested(world,x,y,z,meta,player);
            if (block.removedByPlayer(world,player,x,y,z,false)) {
                block.onBlockDestroyedByPlayer(world,x,y,z,meta);
            }
        }
        // 生存模式逻辑
        else {
            boolean canHarvest = block.canHarvestBlock(player,meta);
            if (stack != null) {
                stack.func_150999_a(world,block,x,y,z,player);
                if (stack.stackSize == 0) {
                    try {
                        player.destroyCurrentEquippedItem();
                    } catch (Throwable ignore) {/*无视风险继续运行*/}
                }
            }
            if (canHarvest) {
                if (removeBlock(block,meta,pos,true)) {
                    block.harvestBlock(world,player,x,y,z,meta);
                }
            }
        }
    }

    public boolean removeBlock(Block block, int meta, Vector3i pos, boolean canHarvest) {
        int x = pos.x;int y = pos.y;int z = pos.z;
        block.onBlockHarvested(world,x,y,z,meta,player);
        if (block.removedByPlayer(world,player,x,y,z,canHarvest)) {
            block.onBlockDestroyedByPlayer(world,x,y,z,meta);
            return true;
        }
        return false;
    }

    public BlockEvent.BreakEvent copyOnBlockBreakEvent(WorldSettings.GameType type,Vector3i pos) {
        boolean preCancelEvent = false;
        int x = pos.x;int y = pos.y;int z = pos.z;
        if (type.isAdventure() && !player.isCurrentToolAdventureModeExempt(x,y,z)) {
            preCancelEvent = true;
        }
        else if (type.isCreative() && player.getHeldItem() != null && player.getHeldItem().getItem() instanceof ItemSword) {
            preCancelEvent = true;
        }

        if (world.getTileEntity(x,y,z) == null) {
            S23PacketBlockChange packet = new S23PacketBlockChange(x,y,z,world);
            packet.field_148883_d = Blocks.air;
            packet.field_148884_e = 0;
            wrapSendPacket(packet);
        }

        Block block = world.getBlock(x,y,z);
        int meta = world.getBlockMetadata(x,y,z);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(x,y,z,world,block,meta,player);
        event.setCanceled(preCancelEvent);
        MinecraftForge.EVENT_BUS.post(event);

        if (event.isCanceled()) {
            wrapSendPacket(new S23PacketBlockChange(x,y,z,world));
            TileEntity tileEntity = world.getTileEntity(x,y,z);
            if (tileEntity != null) {
                Packet packet = tileEntity.getDescriptionPacket();
                if (packet != null) {
                    wrapSendPacket(packet);
                }
            }
        }
        return event;
    }

    public void wrapSendPacket(Packet packet) {
        if (player instanceof EntityPlayerMP playerMP) {
            if (playerMP.playerNetServerHandler != null) {
                playerMP.playerNetServerHandler.sendPacket(packet);
            }
        }
    }







    private void gtOreHarvestBlockBefore(TileEntity tileEntity, Block block, EntityPlayer player) {
        if (!CheckCompatibility.isHasClass_TileEntityOre){
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
        if (!CheckCompatibility.isHasClass_TileEntityOre){
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

    public WorldSettings.GameType selectType() {
        if (player.capabilities.isCreativeMode) {
            return WorldSettings.GameType.CREATIVE;
        } else {
            return WorldSettings.GameType.SURVIVAL;
        }
    }
}
