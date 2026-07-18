package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.client.KeyListener;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolHarvestEligibility;
import club.heiqi.qz_miner.toolswap.minecraft.AutoToolSwapStackStateFactory;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

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
        try {
            ChainSubMode selected = MyMod.chainStateService == null
                    ? null : MyMod.chainStateService.getClientState().getSelectedSubMode();
            boolean breakCapable = ChainSubModeRegistry.getTrigger(selected) == ChainSubModeTrigger.BREAK_BLOCK;
            ToolSwapTargetIdentity targetIdentity = captureTargetIdentity(
                    minecraft.theWorld, minecraft.objectMouseOver);
            return new ToolSwapLightContext(
                    tick,
                    breakCapable,
                    player.capabilities.isCreativeMode,
                    minecraft.currentScreen != null,
                    chainActive,
                    player.inventory.currentItem,
                    targetIdentity);
        } catch (RuntimeException failure) {
            noteCaptureFailure(failure);
            return null;
        } catch (LinkageError failure) {
            noteCaptureFailure(failure);
            return null;
        }
    }

    @Override
    public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
            int anchorSlot, int candidateSlot, int targetBlockId, int targetBlockMetadata) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft == null ? null : minecraft.thePlayer;
        if (light == null || minecraft == null || player == null || minecraft.theWorld == null) {
            return null;
        }
        ToolSwapInventorySnapshot inventory;
        try {
            inventory = captureInventory(minecraft, player, plan, anchorSlot, candidateSlot,
                    targetBlockId, targetBlockMetadata, light.targetIdentity);
        } catch (RuntimeException failure) {
            noteCaptureFailure(failure);
            inventory = ToolSwapInventorySnapshot.untrusted();
        } catch (LinkageError failure) {
            noteCaptureFailure(failure);
            inventory = ToolSwapInventorySnapshot.untrusted();
        }
        boolean guiOpen = minecraft.currentScreen != null;
        boolean transactionSafe = inventory.isTrusted()
                && !light.creative
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
            ToolSwapCapturePlan plan, int anchorSlot, int candidateSlot,
            int targetBlockId, int targetBlockMetadata, ToolSwapTargetIdentity lightTarget) {
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
        ToolSwapTargetIdentity targetIdentity = plan == ToolSwapCapturePlan.FULL_TARGET
                ? ToolSwapTargetIdentity.present(targetBlockId, targetBlockMetadata) : lightTarget;
        if (targetIdentity == null) throw new IllegalArgumentException("lightTarget must not be null");
        Block target = targetIdentity.isPresent() ? Block.getBlockById(targetIdentity.blockId()) : null;
        int metadata = targetIdentity.isPresent() ? targetIdentity.metadata() : 0;
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

    /** 将一次准星命中归一为不含坐标的方块身份。 */
    static ToolSwapTargetIdentity captureTargetIdentity(World world, MovingObjectPosition hit) {
        if (world == null || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return ToolSwapTargetIdentity.ABSENT;
        }
        Block block = world.getBlock(hit.blockX, hit.blockY, hit.blockZ);
        int blockId = block == null ? 0 : Block.getIdFromBlock(block);
        if (blockId <= 0) return ToolSwapTargetIdentity.ABSENT;
        return ToolSwapTargetIdentity.present(blockId,
                world.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ));
    }

    private void noteCaptureFailure(Throwable failure) {
        long now = System.nanoTime();
        if (lastCaptureFailureLogNanos == Long.MIN_VALUE
                || now - lastCaptureFailureLogNanos >= CAPTURE_LOG_INTERVAL_NS) {
            lastCaptureFailureLogNanos = now;
            MyMod.LOG.warn("[AutoToolSwap] Client fact capture failed; snapshot discarded", failure);
        }
    }

    /** 普通可损耗物品不把 durability damage 当作 subtype。 */
    static int stableSubtype(ItemStack stack) {
        return ToolHarvestEligibility.stableSubtype(stack);
    }

    private static SlotSnapshot snapshotSlot(int slot, ItemStack stack) {
        AutoToolSwapStackState state = AutoToolSwapStackStateFactory.capture(stack);
        return new SlotSnapshot(slot, state.roleKey(), state.contentFingerprint());
    }

    private static ToolCandidate snapshotCandidate(int slot, ItemStack stack, Block target, int metadata) {
        return ToolHarvestEligibility.snapshotCandidate(slot, stack, target, metadata);
    }

    /** 目标实际效率必须高于徒手基线。 */
    static boolean isEffective(ItemStack stack, Block target, int metadata) {
        return ToolHarvestEligibility.isEffective(stack, target, metadata);
    }

    /** 仅有采掘等级要求时校验 Forge 工具等级。 */
    static boolean canHarvest(ItemStack stack, Block target, int metadata) {
        return ToolHarvestEligibility.canHarvest(stack, target, metadata);
    }
}
