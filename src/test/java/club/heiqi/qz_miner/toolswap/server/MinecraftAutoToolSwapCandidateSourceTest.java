package club.heiqi.qz_miner.toolswap.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CandidateSnapshot;

/** Minecraft candidate adapter 的 pure ordering 与实时读取结构合同。 */
public class MinecraftAutoToolSwapCandidateSourceTest {

    private static final String CANDIDATE_SOURCE =
            "src/main/java/club/heiqi/qz_miner/toolswap/server/MinecraftAutoToolSwapCandidateSource.java";
    private static final String CANDIDATE_TYPE =
            "club/heiqi/qz_miner/toolswap/server/MinecraftAutoToolSwapCandidateSource";

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

    /**
     * 候选扫描必须重读 live world、复用共享判定，并且只返回纯值结果。
     *
     * <p>原用例逐行匹配实现文本（数组下标写法、循环边界写法、跳过锚点的 if 写法）：
     * 等价重写即误报，而整文件 {@code contains} 也证伪不了它声称守住的回归。
     * 现在改为方法体级的 live 重读判定 + 编译产物成员引用面 + 返回类型/字段类型反射。</p>
     */
    @Test
    public void adapterRereadsTargetHandAndAllInventoryWithoutLeakingMinecraftTypes() throws Exception {
        String scan = JavaSourceSlices.methodBodyWithoutSignature(
                JavaSourceSlices.maskedMainSource(CANDIDATE_SOURCE), "scan");
        Assert.assertTrue("候选扫描必须重读 live block", JavaSourceSlices.mentions(scan, "getBlock"));
        Assert.assertTrue("候选扫描必须重读 live metadata", JavaSourceSlices.mentions(scan, "getBlockMetadata"));

        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(CANDIDATE_TYPE));
        Assert.assertTrue("候选资格必须复用共享判定 ToolHarvestEligibility.snapshotCandidate：" + refs.methodRefs,
                refs.methodRefs.contains("club/heiqi/qz_miner/toolswap/ToolHarvestEligibility#snapshotCandidate"));
        Assert.assertTrue("执行期终裁必须复用 ChainHarvestRules.canHarvest：" + refs.methodRefs,
                refs.methodRefs.contains("club/heiqi/qz_miner/chain/planner/ChainHarvestRules#canHarvest"));

        Method scanMethod = MinecraftAutoToolSwapCandidateSource.class.getMethod(
                "scan", Object.class, ChainTarget.class, List.class);
        Assert.assertEquals("adapter 只能返回纯值 CandidateScan",
                AutoToolSwapServerBatchService.CandidateScan.class, scanMethod.getReturnType());
        for (Field field : AutoToolSwapServerBatchService.CandidateScan.class.getDeclaredFields()) {
            Assert.assertFalse("纯值扫描结果不得持有 Minecraft 类型：" + field,
                    field.getType().getName().startsWith("net.minecraft."));
        }
        // 已删除的三条实现文本断言与理由：
        // ① contains("player.inventory.mainInventory[anchorSlot]")：改用 getStackInSlot 或先取局部数组即误报。
        // ② contains("slot <= AutoToolSwapProtocol.INVENTORY_LAST_SLOT")：循环边界写法，写成别的等价范围判定即误报，
        //    而整文件 contains 也无法证伪「扫描范围被截断」。
        // ③ contains("if (slot == anchorSlot) continue")：跳过写法属实现形状；锚点槽排除在
        //    AutoToolSwapServerBatchService.prepare 路径上另有权威守卫（selected.slot() == session.anchorSlot → SKIP_TARGET）。
    }

    private static CandidateSnapshot candidate(int slot, String item, int durability) {
        AutoToolSwapStackState state = AutoToolSwapStackState.occupied(item,
                AutoToolSwapContentFingerprint.fromContent(item, "slot-" + slot), durability);
        return new CandidateSnapshot(new ToolCandidate(slot, item, 0, Collections.<String>emptyList(),
                true, true, durability), state);
    }
}
