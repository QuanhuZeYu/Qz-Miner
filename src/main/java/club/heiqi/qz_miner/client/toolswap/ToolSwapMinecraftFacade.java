package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.client.KeyListener;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.minecraft.AutoToolSwapStackStateFactory;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Minecraft 客户端事实采样门面。
 *
 * <p>本类只读取库存；绝不直接写库存数组、主手索引或发起原版库存点击。</p>
 */
@SideOnly(Side.CLIENT)
public class ToolSwapMinecraftFacade implements AutoToolSwapClientAdapter.GameFacade {

    private static final long CAPTURE_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);
    private long lastCaptureFailureLogNanos = Long.MIN_VALUE;

    @Override
    public ToolSwapLightContext captureLightContext(long tick, boolean chainActive) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft == null ? null : minecraft.thePlayer;
        if (minecraft == null || player == null || minecraft.theWorld == null) {
            return null;
        }
        ChainSubMode selected = MyMod.chainStateService == null
                ? null : MyMod.chainStateService.getClientState().getSelectedSubMode();
        boolean breakCapable = ChainSubModeRegistry.getTrigger(selected) == ChainSubModeTrigger.BREAK_BLOCK;
        return new ToolSwapLightContext(
                tick,
                breakCapable,
                player.capabilities.isCreativeMode,
                minecraft.currentScreen != null,
                chainActive,
                player.inventory.currentItem);
    }

    @Override
    public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
            int anchorSlot, int candidateSlot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft == null ? null : minecraft.thePlayer;
        if (light == null || minecraft == null || player == null || minecraft.theWorld == null) {
            return null;
        }
        ToolSwapInventorySnapshot inventory;
        try {
            inventory = captureInventory(minecraft, player, plan, anchorSlot, candidateSlot);
        } catch (RuntimeException failure) {
            noteCaptureFailure(failure);
            inventory = ToolSwapInventorySnapshot.untrusted();
        } catch (LinkageError failure) {
            noteCaptureFailure(failure);
            inventory = ToolSwapInventorySnapshot.untrusted();
        }
        boolean guiOpen = minecraft.currentScreen != null;
        boolean transactionSafe = inventory.isTrusted()
                && !guiOpen
                && player.openContainer == player.inventoryContainer
                && player.inventoryContainer != null
                && player.inventoryContainer.windowId == 0
                && player.inventory.getItemStack() == null;
        return new ToolSwapContext(light, transactionSafe, guiOpen,
                player.inventory.currentItem, inventory);
    }

    @Override
    public boolean isChainKeyPhysicallyDown() {
        return KeyListener.chainSwitch != null && KeyListener.chainSwitch.getIsKeyPressed();
    }

    /** 按计划原子捕获库存；任一回调失败由调用方丢弃全部部分结果。 */
    ToolSwapInventorySnapshot captureInventory(Minecraft minecraft, EntityPlayer player,
            ToolSwapCapturePlan plan, int anchorSlot, int candidateSlot) {
        if (plan == ToolSwapCapturePlan.NONE) {
            return ToolSwapInventorySnapshot.none();
        }
        if (plan == ToolSwapCapturePlan.PROTECTED) {
            if (anchorSlot < 0 || candidateSlot < 0) {
                return ToolSwapInventorySnapshot.untrusted();
            }
            List<SlotSnapshot> protectedSlots = new ArrayList<SlotSnapshot>(2);
            protectedSlots.add(snapshotSlot(anchorSlot, player.inventory.mainInventory[anchorSlot]));
            protectedSlots.add(snapshotSlot(candidateSlot, player.inventory.mainInventory[candidateSlot]));
            return ToolSwapInventorySnapshot.protectedSlots(protectedSlots);
        }
        Block target = null;
        int metadata = 0;
        MovingObjectPosition hit = minecraft.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            target = minecraft.theWorld.getBlock(hit.blockX, hit.blockY, hit.blockZ);
            metadata = minecraft.theWorld.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ);
        }
        List<SlotSnapshot> slots = new ArrayList<SlotSnapshot>(36);
        List<ToolCandidate> candidates = new ArrayList<ToolCandidate>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            slots.add(snapshotSlot(slot, stack));
            ToolCandidate candidate = snapshotCandidate(slot, stack, target, metadata);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return new ToolSwapInventorySnapshot(slots, candidates);
    }

    private void noteCaptureFailure(Throwable failure) {
        long now = System.nanoTime();
        if (lastCaptureFailureLogNanos == Long.MIN_VALUE
                || now - lastCaptureFailureLogNanos >= CAPTURE_LOG_INTERVAL_NS) {
            lastCaptureFailureLogNanos = now;
            MyMod.LOG.warn("[AutoToolSwap] Inventory capture failed; snapshot discarded", failure);
        }
    }

    /** 普通可损耗物品不把 durability damage 当作 subtype。 */
    static int stableSubtype(ItemStack stack) {
        return stack != null && stack.getItem() != null && stack.getItem().getHasSubtypes()
                ? stack.getItemDamage() : 0;
    }

    private static SlotSnapshot snapshotSlot(int slot, ItemStack stack) {
        AutoToolSwapStackState state = AutoToolSwapStackStateFactory.capture(stack);
        return new SlotSnapshot(slot, state.roleKey(), state.contentFingerprint());
    }

    private static ToolCandidate snapshotCandidate(int slot, ItemStack stack, Block target, int metadata) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        Item item = stack.getItem();
        boolean effective = isEffective(stack, target, metadata);
        boolean canHarvest = canHarvest(stack, target, metadata);
        int remaining = stack.isItemStackDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getItemDamage()) : Integer.MAX_VALUE;
        return new ToolCandidate(
                slot,
                registryId(item),
                stableSubtype(stack),
                oreNames(stack),
                effective,
                canHarvest,
                remaining);
    }

    /** 目标实际效率必须高于徒手基线。 */
    static boolean isEffective(ItemStack stack, Block target, int metadata) {
        return stack != null && stack.getItem() != null && target != null
                && stack.getItem().getDigSpeed(stack, target, metadata) > 1.0F;
    }

    /** 仅有采掘等级要求时校验 Forge 工具等级。 */
    static boolean canHarvest(ItemStack stack, Block target, int metadata) {
        return stack != null && target != null && (target.getHarvestTool(metadata) == null
                || ForgeHooks.canToolHarvestBlock(target, metadata, stack));
    }

    private static String registryId(Item item) {
        Object name = Item.itemRegistry.getNameForObject(item);
        return name == null ? "minecraft:unknown" : String.valueOf(name);
    }

    private static List<String> oreNames(ItemStack stack) {
        int[] ids = OreDictionary.getOreIDs(stack);
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<String>(ids.length);
        for (int id : ids) {
            String name = OreDictionary.getOreName(id);
            if (name != null && name.length() > 0) {
                names.add(name);
            }
        }
        return Collections.unmodifiableList(names);
    }
}
