package club.heiqi.qz_miner.toolswap.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.qz_miner.chain.planner.ChainHarvestRules;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolCandidateOrder;
import club.heiqi.qz_miner.toolswap.ToolHarvestEligibility;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/**
 * Minecraft 服务端主线程候选读取边界。
 *
 * <p>每次调用都重读 live block/meta、选中槽、当前手和 0..35 inventory；返回值不持有
 * ItemStack、World 或 Block。selector 首次命中优先，同优先级固定按槽号排序。</p>
 */
public final class MinecraftAutoToolSwapCandidateSource
        implements AutoToolSwapServerBatchService.CandidateSource {

    /** 捕获单目标的实时工具事实。 */
    @Override
    public AutoToolSwapServerBatchService.CandidateScan scan(Object endpoint, ChainTarget target,
            List<ToolSelector> selectors) {
        if (!(endpoint instanceof EntityPlayerMP)) return null;
        EntityPlayerMP player = (EntityPlayerMP) endpoint;
        Object world = player.worldObj;
        int anchorSlot = player.inventory == null ? -1 : player.inventory.currentItem;
        if (world == null || player.inventory == null || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot)) {
            return AutoToolSwapServerBatchService.CandidateScan.classified(
                    AutoToolSwapServerBatchService.ScanStatus.INVENTORY_UNSAFE,
                    world, player.dimension, anchorSlot);
        }
        if (target == null) {
            return AutoToolSwapServerBatchService.CandidateScan.classified(
                    AutoToolSwapServerBatchService.ScanStatus.TARGET_INVALID,
                    world, player.dimension, anchorSlot);
        }

        MinecraftAutoToolSwapInventoryPort inventory = new MinecraftAutoToolSwapInventoryPort(player);
        try {
            if (!inventory.isPlayerAlive() || !inventory.hasPersonalInventoryWindow0()
                    || !inventory.isCursorEmpty()) {
                return AutoToolSwapServerBatchService.CandidateScan.classified(
                        AutoToolSwapServerBatchService.ScanStatus.INVENTORY_UNSAFE,
                        world, player.dimension, anchorSlot);
            }
            if (inventory.isCreativeMode()) {
                return AutoToolSwapServerBatchService.CandidateScan.classified(
                        AutoToolSwapServerBatchService.ScanStatus.BYPASS,
                        world, player.dimension, anchorSlot);
            }

            Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
            int metadata = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
            int blockId = block == null ? 0 : Block.getIdFromBlock(block);
            if (block == null || block == Blocks.air || blockId <= 0 || metadata < 0) {
                return AutoToolSwapServerBatchService.CandidateScan.classified(
                        AutoToolSwapServerBatchService.ScanStatus.TARGET_INVALID,
                        world, player.dimension, anchorSlot);
            }

            ItemStack hand = player.inventory.mainInventory[anchorSlot];
            AutoToolSwapStackState handState = inventory.readInventorySlot(anchorSlot);
            List<AutoToolSwapServerBatchService.CandidateSnapshot> candidates =
                    new ArrayList<AutoToolSwapServerBatchService.CandidateSnapshot>();
            for (int slot = AutoToolSwapProtocol.INVENTORY_FIRST_SLOT;
                    slot <= AutoToolSwapProtocol.INVENTORY_LAST_SLOT; slot++) {
                if (slot == anchorSlot) continue;
                ItemStack stack = player.inventory.mainInventory[slot];
                ToolCandidate candidate = ToolHarvestEligibility.snapshotCandidate(slot, stack, block, metadata);
                if (candidate == null || !candidate.isEligibleForSwap()) continue;
                AutoToolSwapStackState state = inventory.readInventorySlot(slot);
                if (state != null && !state.isEmpty()) {
                    candidates.add(new AutoToolSwapServerBatchService.CandidateSnapshot(candidate, state));
                }
            }
            return AutoToolSwapServerBatchService.CandidateScan.ready(world, player.dimension, anchorSlot,
                    new AutoToolSwapServerBatchService.TargetIdentity(blockId, metadata), handState,
                    ToolHarvestEligibility.isEligible(hand, block, metadata),
                    orderCandidates(candidates, selectors));
        } catch (RuntimeException failure) {
            return AutoToolSwapServerBatchService.CandidateScan.classified(
                    AutoToolSwapServerBatchService.ScanStatus.TARGET_INVALID,
                    world, player.dimension, anchorSlot);
        } catch (LinkageError failure) {
            return AutoToolSwapServerBatchService.CandidateScan.classified(
                    AutoToolSwapServerBatchService.ScanStatus.TARGET_INVALID,
                    world, player.dimension, anchorSlot);
        }
    }

    /** mutation 前再次读取 block/meta，阻止候选扫描后的目标漂移。 */
    @Override
    public boolean targetStillMatches(Object endpoint, ChainTarget target,
            AutoToolSwapServerBatchService.TargetIdentity identity) {
        if (!(endpoint instanceof EntityPlayerMP) || target == null || identity == null) return false;
        EntityPlayerMP player = (EntityPlayerMP) endpoint;
        if (player.worldObj == null) return false;
        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        int blockId = block == null ? 0 : Block.getIdFromBlock(block);
        int metadata = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return blockId == identity.blockId() && metadata == identity.metadata();
    }

    /** mutation 后仍由既有执行期规则终裁当前真实主手。 */
    @Override
    public boolean canHarvest(Object endpoint, ChainTarget target) {
        return endpoint instanceof EntityPlayerMP
                && ChainHarvestRules.canHarvest((EntityPlayerMP) endpoint, target);
    }

    /** 纯值排序接缝：selector 优先，槽 0..35 稳定 tie-break。 */
    static List<AutoToolSwapServerBatchService.CandidateSnapshot> orderCandidates(
            List<AutoToolSwapServerBatchService.CandidateSnapshot> snapshots,
            List<ToolSelector> selectors) {
        if (snapshots == null || snapshots.isEmpty()) return Collections.emptyList();
        Map<Integer, AutoToolSwapServerBatchService.CandidateSnapshot> bySlot =
                new HashMap<Integer, AutoToolSwapServerBatchService.CandidateSnapshot>();
        List<ToolCandidate> values = new ArrayList<ToolCandidate>();
        for (AutoToolSwapServerBatchService.CandidateSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            bySlot.put(Integer.valueOf(snapshot.slot()), snapshot);
            values.add(snapshot.candidate());
        }
        List<AutoToolSwapServerBatchService.CandidateSnapshot> result =
                new ArrayList<AutoToolSwapServerBatchService.CandidateSnapshot>();
        for (ToolCandidate candidate : ToolCandidateOrder.sort(values, selectors)) {
            AutoToolSwapServerBatchService.CandidateSnapshot snapshot =
                    bySlot.get(Integer.valueOf(candidate.slot()));
            if (snapshot != null) result.add(snapshot);
        }
        return Collections.unmodifiableList(result);
    }
}
