package club.heiqi.qz_miner.minerModes.breaker;

import appeng.tile.AEBaseTile;
import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerModes.utils.Utils;
import club.heiqi.qz_miner.mixins.GTMixin.BWTileEntityMetaGeneratedOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.BlockBaseOreAccessor;
import club.heiqi.qz_miner.mixins.GTMixin.TileEntityOresAccessor;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.TileEntityOres;
import gtPlusPlus.core.block.base.BlockBaseOre;
import net.minecraft.block.*;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ReportedException;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.block.Block.getIdFromBlock;

/**
 * 采掘者类，存储了当前挖掘的世界和进行挖掘的玩家<br>
 * 仅限服务端运行!
 */
public class BlockBreaker {
    public Logger LOG = LogManager.getLogger();
    @Nullable
    public EntityPlayerMP player;
    public World world;
    public List<ItemStack> drops = new ArrayList<>();

    public BlockBreaker(EntityPlayer player, World world) {
        if (player instanceof EntityPlayerMP playerMP) this.player = playerMP;
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
        }
        player.theItemInWorldManager.tryHarvestBlock(x,y,z);
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
            harvestBlock(block, x, y, z, metadata);
        }
        return isRemoved;
    }

    public void harvestBlock(Block block, int x, int y, int z, int meta) {
        // 重写了harvestBlock的
        if (block instanceof BlockDeadBush || block instanceof BlockDoublePlant
            || block instanceof BlockFlower || block instanceof BlockIce
            || block instanceof BlockLeaves || block instanceof BlockSnow
            || block instanceof BlockTallGrass || block instanceof  BlockVine) {
            block.harvestBlock(world, player, x, y, z, meta);
            return;
        }
        player.addStat(StatList.mineBlockStatArray[getIdFromBlock(block)], 1);
        player.addExhaustion(0.025F);

        if (block.renderAsNormalBlock() && !block.hasTileEntity(meta) && EnchantmentHelper.getSilkTouchModifier(player)) {
            ArrayList<ItemStack> items = new ArrayList<>();

            Item item = Item.getItemFromBlock(block);
            if (item == null || !item.getHasSubtypes()) {
                meta = 0;
            }
            ItemStack itemstack = new ItemStack(item, 1, meta);

            items.add(itemstack);

            ForgeEventFactory.fireBlockHarvesting(items, world, block, x, y, z, meta, 0, 1.0f, true, player);
            for (ItemStack is : items) {
                dropBlockAsItem(x, y, z, is);
            }
        }
        else {
            int fortune = EnchantmentHelper.getFortuneModifier(player);
            int i1 = EnchantmentHelper.getFortuneModifier(player);
            ArrayList<ItemStack> items = block.getDrops(world, x, y, z, meta, fortune);
            float chance = ForgeEventFactory.fireBlockHarvesting(items, world, block, x, y, z, meta, fortune, 1, false, player);
            for (ItemStack item : items)
            {
                if (world.rand.nextFloat() <= chance)
                {
                    this.dropBlockAsItem(x, y, z, item);
                }
            }
        }
    }

    public void dropBlockAsItem(int x, int y, int z, ItemStack itemIn) {
        if (!world.isRemote && world.getGameRules().getGameRuleBooleanValue("doTileDrops") && !world.restoringBlockSnapshots) {
            float f = 0.7F;
            double d0 = (double)(world.rand.nextFloat() * f) + (double)(1.0F - f) * 0.5D;
            double d1 = (double)(world.rand.nextFloat() * f) + (double)(1.0F - f) * 0.5D;
            double d2 = (double)(world.rand.nextFloat() * f) + (double)(1.0F - f) * 0.5D;
            if (Config.dropItemToSelf) {
                Vector3f dropPos = Utils.getItemDropPos(player);
                x = (int) Math.floor(dropPos.x);
                y = (int) Math.floor(dropPos.y);
                z = (int) Math.floor(dropPos.z);
            }
            EntityItem entityitem = new EntityItem(world, (double)x + d0, (double)y + d1, (double)z + d2, itemIn);
            entityitem.delayBeforeCanPickup = 10;

            if (!addItemStackToInventory(itemIn))
                world.spawnEntityInWorld(entityitem);
        }
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
        PlayerInteractEvent interact = new PlayerInteractEvent(player, PlayerInteractEvent.Action.LEFT_CLICK_BLOCK, x, y, z, ForgeDirection.UP.flag, world);
        MinecraftForge.EVENT_BUS.post(interact);
        canHarvest = true;
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



    public boolean addItemStackToInventory(ItemStack itemStack) {
        if (itemStack == null || itemStack.stackSize == 0 || itemStack.getItem() == null) {
            return false;
        }

        ItemStack[] mainInventory = player.inventory.mainInventory;
        boolean isCreativeMode = player.capabilities.isCreativeMode;

        try {
            if (itemStack.isItemDamaged()) {
                return handleDamagedItem(itemStack, mainInventory, isCreativeMode);
            } else {
                return handleUndamagedItem(itemStack, mainInventory, isCreativeMode);
            }
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Adding item to inventory");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Item being added");
            crashreportcategory.addCrashSection("Item ID", Item.getIdFromItem(itemStack.getItem()));
            crashreportcategory.addCrashSection("Item data", itemStack.getItemDamage());
            crashreportcategory.addCrashSectionCallable("Item name", itemStack::getDisplayName);
            throw new ReportedException(crashreport);
        }
    }

    private static boolean handleDamagedItem(ItemStack itemStack, ItemStack[] mainInventory, boolean isCreativeMode) {
        int slot = findFirstEmptySlot(mainInventory);
        if (slot >= 0) {
            mainInventory[slot] = ItemStack.copyItemStack(itemStack);
            mainInventory[slot].animationsToGo = 5;
            itemStack.stackSize = 0;
            return true;
        } else if (isCreativeMode) {
            itemStack.stackSize = 0;
            return true;
        } else {
            return false;
        }
    }

    private static int findFirstEmptySlot(ItemStack[] mainInventory) {
        for (int i = 0; i < mainInventory.length; i++) {
            if (mainInventory[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private static boolean handleUndamagedItem(ItemStack itemStack, ItemStack[] mainInventory, boolean isCreativeMode) {
        int originalSize = itemStack.stackSize;
        int currentSize;
        do {
            currentSize = itemStack.stackSize;
            itemStack.stackSize = storePartialItemStack(itemStack, mainInventory);
        } while (itemStack.stackSize > 0 && itemStack.stackSize < currentSize);

        if (itemStack.stackSize == originalSize && isCreativeMode) {
            itemStack.stackSize = 0;
            return true;
        } else {
            return itemStack.stackSize < originalSize;
        }
    }

    private static int storePartialItemStack(ItemStack itemStack, ItemStack[] mainInventory) {
        int remaining = itemStack.stackSize;
        int maxStackSize = itemStack.getMaxStackSize();

        // 尝试合并到现有堆栈
        for (int i = 0; i < mainInventory.length && remaining > 0; i++) {
            ItemStack slotStack = mainInventory[i];
            if (slotStack != null && canStackAdd(slotStack, itemStack)) {
                int space = maxStackSize - slotStack.stackSize;
                int transfer = Math.min(space, remaining);
                if (transfer > 0) {
                    slotStack.stackSize += transfer;
                    remaining -= transfer;
                }
            }
        }

        // 存入空槽
        if (remaining > 0) {
            int emptySlot = findFirstEmptySlot(mainInventory);
            if (emptySlot >= 0) {
                ItemStack newStack = ItemStack.copyItemStack(itemStack);
                newStack.stackSize = remaining;
                mainInventory[emptySlot] = newStack;
                remaining = 0;
            }
        }

        return remaining;
    }

    private static boolean canStackAdd(ItemStack existing, ItemStack addition) {
        return existing.getItem() == addition.getItem()
            && existing.isStackable()
            && existing.getItemDamage() == addition.getItemDamage()
            && ItemStack.areItemStackTagsEqual(existing, addition)
            && existing.stackSize < existing.getMaxStackSize();
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
