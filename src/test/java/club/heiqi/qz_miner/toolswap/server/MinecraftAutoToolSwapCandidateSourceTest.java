package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CandidateSnapshot;

/** Minecraft candidate adapter 的 pure ordering 与实时读取结构合同。 */
public class MinecraftAutoToolSwapCandidateSourceTest {

    @Test
    public void selectorPriorityThenSlotNumberIsStableAndLowReserveIsFiltered() {
        CandidateSnapshot slot8 = candidate(8, "mod:plain", 10);
        CandidateSnapshot slot3 = candidate(3, "mod:plain", 10);
        CandidateSnapshot preferred = candidate(20, "mod:preferred", 10);
        CandidateSnapshot exhausted = candidate(1, "mod:preferred", 1);

        List<CandidateSnapshot> ordered = MinecraftAutoToolSwapCandidateSource.orderCandidates(
                Arrays.asList(slot8, preferred, exhausted, slot3),
                Collections.singletonList(ToolSelector.itemWildcard("mod:preferred")));

        Assert.assertEquals(3, ordered.size());
        Assert.assertEquals(20, ordered.get(0).slot());
        Assert.assertEquals(3, ordered.get(1).slot());
        Assert.assertEquals(8, ordered.get(2).slot());
    }

    @Test
    public void adapterRereadsTargetHandAndAllInventoryWithoutLeakingMinecraftTypes() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/toolswap/server/MinecraftAutoToolSwapCandidateSource.java")
                .toPath()), StandardCharsets.UTF_8);

        Assert.assertTrue(source.contains("player.worldObj.getBlock(target.getX(), target.getY(), target.getZ())"));
        Assert.assertTrue(source.contains("player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ())"));
        Assert.assertTrue(source.contains("player.inventory.mainInventory[anchorSlot]"));
        Assert.assertTrue(source.contains("slot <= AutoToolSwapProtocol.INVENTORY_LAST_SLOT"));
        Assert.assertTrue(source.contains("if (slot == anchorSlot) continue"));
        Assert.assertTrue(source.contains("ToolHarvestEligibility.snapshotCandidate"));
        Assert.assertTrue(source.contains("ChainHarvestRules.canHarvest"));
        Assert.assertTrue("adapter 返回值只能是 pure CandidateScan",
                source.contains("AutoToolSwapServerBatchService.CandidateScan scan"));
    }

    private static CandidateSnapshot candidate(int slot, String item, int durability) {
        AutoToolSwapStackState state = AutoToolSwapStackState.occupied(item,
                AutoToolSwapContentFingerprint.fromContent(item, "slot-" + slot), durability);
        return new CandidateSnapshot(new ToolCandidate(slot, item, 0, Collections.<String>emptyList(),
                true, true, durability), state);
    }
}
