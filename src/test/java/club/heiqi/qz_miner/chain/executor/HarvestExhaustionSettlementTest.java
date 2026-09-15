package club.heiqi.qz_miner.chain.executor;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.FoodStats;

/**
 * Issue #242 饥饿值消耗「覆盖」口径的契约（纯 JVM，不加载 GL / 不跑客户端）。
 *
 * <p>用户判据（2026-09-15）：玩家按下连锁后经执行器破坏的每个方块，饥饿值消耗必须等于
 * {@code general.harvestExhaustionPerBlock} 配置值——默认 0.025 等于原版，{@code 0} 表示完全不消耗，
 * 负值表示净回补。本类按「实测增量」结算，这里冻结两条等式：</p>
 * <ul>
 *   <li>实测增量 + 追加量 == 配置值，对覆写 {@code harvestBlock}、原版增量为 0 的方块同样成立；</li>
 *   <li>实测增量落在原版常量容差内时按常量 0.025 结算，默认档因此精确得到 0 增量、不额外调用。</li>
 * </ul>
 */
public class HarvestExhaustionSettlementTest {

    private static final float VANILLA = HarvestExhaustionSettlement.VANILLA_HARVEST_EXHAUSTION;
    private static final float TOLERANCE = HarvestExhaustionSettlement.CONSTANT_BASELINE_TOLERANCE;

    /** 默认档 + 基类路径：追加量必须精确为 0（不调用 addExhaustion）。 */
    @Test
    public void defaultConfigurationAddsNothingForVanillaBlocks() {
        Assert.assertEquals("默认档不得追加任何 exhaustion", 0.0F, net(VANILLA, VANILLA), 0.0F);
        Assert.assertEquals("必须逐位为 +0.0F（-0.0F 会多走一次 addExhaustion）",
                0, Float.floatToRawIntBits(net(VANILLA, VANILLA)));
    }

    /** 基类路径 0.025 经「累加后读回」带 float 噪声，必须被容差吸收，默认档仍不调用。 */
    @Test
    public void defaultConfigurationAbsorbsRoundTripNoise() {
        Assert.assertEquals(0.0F, net(VANILLA, VANILLA + 4.0E-6F), 0.0F);
        Assert.assertEquals(0.0F, net(VANILLA, VANILLA - 4.0E-6F), 0.0F);
        Assert.assertEquals(0.0F, net(VANILLA, VANILLA + 0.9F * TOLERANCE), 0.0F);
    }

    /** 0 档 = 连锁完全不消耗：追加量恰好抵消实测增量；原版没消耗时也不回补。 */
    @Test
    public void zeroConfigurationCancelsTheObservedCost() {
        Assert.assertEquals(-VANILLA, net(0.0F, VANILLA), 0.0F);
        Assert.assertEquals(0.0F, net(0.0F, 0.0F), 0.0F);
    }

    /** 覆盖语义：无论原版实际消耗多少，实测增量 + 追加量都等于配置值。 */
    @Test
    public void everyConfiguredValueOverridesWhateverVanillaConsumed() {
        float[] configured = {-40.0F, -1.0F, -0.5F, 0.0F, 0.01F, 0.025F, 0.1F, 0.5F, 40.0F};
        float[] observed = {0.0F, VANILLA, 0.05F, -0.01F};
        for (float value : configured) {
            for (float seen : observed) {
                float total = seen + net(value, seen);
                Assert.assertEquals("净结算必须等于配置值 cfg=" + value + " observed=" + seen,
                        value, total, 1.0E-4F);
            }
        }
    }

    /** 覆写 harvestBlock、原版增量为 0 的方块同样被覆盖（旧「补差额」口径会差 0.025）。 */
    @Test
    public void blocksThatSkipVanillaHarvestBlockAreStillOverridden() {
        Assert.assertEquals(VANILLA, net(VANILLA, 0.0F), 0.0F);
        Assert.assertEquals(0.0F, net(0.0F, 0.0F), 0.0F);
        Assert.assertEquals(-1.0F, net(-1.0F, 0.0F), 0.0F);
    }

    /** 方向性：低于原版（含 0 与负数）追加量为负＝净回补，高于原版追加量为正。 */
    @Test
    public void deltaSignFollowsConfiguredValue() {
        Assert.assertTrue(net(0.0F, VANILLA) < 0.0F);
        Assert.assertTrue(net(-0.5F, VANILLA) < 0.0F);
        Assert.assertTrue(net(0.01F, VANILLA) < 0.0F);
        Assert.assertTrue(net(0.1F, VANILLA) > 0.0F);
        Assert.assertTrue("负值必须比 0 档回补更多", net(-0.5F, VANILLA) < net(0.0F, VANILLA));
    }

    /** 非有限值：配置非有限不追加；实测不可用（NaN / ±Inf）退回常量口径。 */
    @Test
    public void nonFiniteValuesFallBackToTheDocumentedBaseline() {
        Assert.assertEquals(0.0F, net(Float.NaN, VANILLA), 0.0F);
        Assert.assertEquals(0.0F, net(Float.POSITIVE_INFINITY, 0.0F), 0.0F);
        Assert.assertEquals(0.0F, net(Float.NEGATIVE_INFINITY, 0.0F), 0.0F);
        Assert.assertEquals("实测不可用时退回常量口径", -VANILLA, net(0.0F, Float.NaN), 0.0F);
        Assert.assertEquals(VANILLA, net(0.05F, Float.NEGATIVE_INFINITY), 1.0E-7F);
    }

    /** 容差必须远大于 float 往返噪声、远小于任何可感知的每方块差异。 */
    @Test
    public void constantToleranceStaysBetweenNoiseAndPerceptibleDeltas() {
        Assert.assertTrue("容差要能吸收 40 附近的 ulp 级噪声", TOLERANCE > 1.0E-5F);
        Assert.assertTrue("容差不得吞掉可感知档位", TOLERANCE < 1.0E-3F);
    }

    /**
     * NBT key 必须与原版 {@code writeNBT} 输出一致：key 写错不会抛异常，只会让读数恒为 0，
     * 使覆盖整体偏移一个原版常量（本用例把这种静默失效变成可回归项）。
     */
    @Test
    public void exhaustionNbtKeyMatchesVanillaSerialization() {
        NBTTagCompound tag = new NBTTagCompound();
        new FoodStats().writeNBT(tag);
        Assert.assertTrue("原版 writeNBT 必须写入本类读取的 key: "
                        + HarvestExhaustionSettlement.EXHAUSTION_NBT_KEY,
                tag.hasKey(HarvestExhaustionSettlement.EXHAUSTION_NBT_KEY));
    }

    private static float net(float configured, float observed) {
        return HarvestExhaustionSettlement.netDelta(configured, observed);
    }
}
