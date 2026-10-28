package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

/**
 * 连锁/爆破额外目标成功破坏后，以破坏前后的 exhaustion 差值结算配置消耗（Issue #242）。
 *
 * <p>调用方在同一次服务端主线程收获前采样、成功后结算。原版和模组方块的 harvestBlock 路径
 * 可产生不同增量，追加量取配置值减实测基线；同次调用内的其他 exhaustion 变化也计入基线。
 * 读数失败回退原版常量，结算异常记录日志并保留已完成的破坏结果。
 * 真实服务端玩家适用，起点手动挖掘、INTERACT、SPECIAL 沿各自执行路径。</p>
 *
 * <p>上游口径：{@code net/minecraft/block/Block.java:1195} 追加原版常量；
 * {@code net/minecraft/block/BlockDoublePlant.java:182-188} 的剪切分支可自行产出掉落；
 * {@code net/minecraft/server/management/ItemInWorldManager.java:322-325} 在成功移除后收获；
 * {@code net/minecraft/util/FoodStats.java:108-114} 通过 writeNBT 暴露 exhaustion；
 * {@code net/minecraft/util/FoodStats.java:141-144} 仅夹取累加池上界，负追加量可净回补；
 * {@code net/minecraft/entity/player/EntityPlayer.java:2137-2146} 由 disableDamage 屏蔽创造模式消耗。</p>
 *
 * <p>接近原版累加池上限时，净消耗受上界截断；常量附近的 float 舍入由容差吸收。
 * 非有限配置返回零追加量，配置合法域由生产 schema 维护。</p>
 */
public final class HarvestExhaustionSettlement {

    /**
     * 原版 {@code Block.harvestBlock} 对每次实际收获固定追加的 exhaustion。
     *
     * <p>本类只在「实测增量就是这一次固定消耗」时用它作为基线口径，见
     * {@link #CONSTANT_BASELINE_TOLERANCE}。</p>
     */
    public static final float VANILLA_HARVEST_EXHAUSTION = 0.025F;

    /** 实测增量接近原版常量时使用常量基线，吸收累加与读回的 float 舍入。 */
    static final float CONSTANT_BASELINE_TOLERANCE = 1.0E-4F;

    /** 原版 FoodStats 的 exhaustion 序列化键，与 writeNBT 读取口径一致。 */
    static final String EXHAUSTION_NBT_KEY = "foodExhaustionLevel";

    private HarvestExhaustionSettlement() {
    }

    /**
     * 破坏前采集 exhaustion 基线。
     *
     * <p>读取异常或实体不可用时返回 {@link Float#NaN}，后续结算使用常量基线，破坏流程继续。</p>
     *
     * @param player 触发本次破坏的在线服务端玩家（服务端主线程调用）
     * @return 当前 exhaustion 累加值；不可用时为 {@code NaN}
     */
    public static float captureExhaustion(EntityPlayerMP player) {
        if (player == null) {
            return Float.NaN;
        }
        try {
            return readExhaustion(player);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[HarvestExhaustionSettlement] Failed to read exhaustion baseline for player {},"
                    + " falling back to the vanilla constant", player.getUniqueID(), failure);
            return Float.NaN;
        }
    }

    /**
     * 在真实破坏成功后将本次消耗覆盖为配置值。
     *
     * @param player           触发本次破坏的在线服务端玩家（服务端主线程调用）
     * @param exhaustionBefore 本次破坏前由 {@link #captureExhaustion} 采集的基线（可为 NaN）
     */
    public static void settleAfterSuccessfulHarvest(EntityPlayerMP player, float exhaustionBefore) {
        try {
            settle(player, exhaustionBefore);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[HarvestExhaustionSettlement] Failed to settle exhaustion for player {}",
                    player == null ? null : player.getUniqueID(), failure);
        }
    }

    /**
     * 计算配置值减基线的追加量；实测不可用或接近原版常量时使用常量基线。
     *
     * @return 追加给 addExhaustion 的增量；非有限配置返回零
     */
    static float netDelta(float configured, float observed) {
        if (!Float.isFinite(configured)) {
            return 0.0F;
        }
        float delta = configured - baselineOf(observed);
        // -0.0F 与 0.0F 相等，同判为「无需调用」
        return delta == 0.0F ? 0.0F : delta;
    }

    /** 把实测增量折算成基线：不可用或等于原版常量时取常量口径，其余取实测值。 */
    private static float baselineOf(float observed) {
        if (!Float.isFinite(observed)) {
            return VANILLA_HARVEST_EXHAUSTION;
        }
        return Math.abs(observed - VANILLA_HARVEST_EXHAUSTION) <= CONSTANT_BASELINE_TOLERANCE
                ? VANILLA_HARVEST_EXHAUSTION
                : observed;
    }

    private static void settle(EntityPlayerMP player, float exhaustionBefore) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) {
            return;
        }
        if (player instanceof FakePlayer) {
            return;
        }
        // 结算一律走 addExhaustion 的 float 口径；配置值先落 float，默认 0.025 才与
        // VANILLA_HARVEST_EXHAUSTION 逐位相等、差额恰好为 0。
        float delta = netDelta((float) Config.harvestExhaustionPerBlock,
                observedDelta(player, exhaustionBefore));
        if (delta == 0.0F) {
            return;
        }
        // 负追加量原样传递，由 FoodStats.addExhaustion 应用累加池上界。
        player.addExhaustion(delta);
    }

    /**
     * 本次破坏原版实际产生的 exhaustion 增量。
     *
     * <p>基线或读数不可用时返回 {@link Float#NaN}，由 {@link #baselineOf} 退回常量口径。</p>
     */
    private static float observedDelta(EntityPlayerMP player, float exhaustionBefore) {
        try {
            return readExhaustion(player) - exhaustionBefore;
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[HarvestExhaustionSettlement] Failed to read exhaustion after harvest for player {},"
                    + " falling back to the vanilla constant", player.getUniqueID(), failure);
            return Float.NaN;
        }
    }

    /** 每次通过 FoodStats.writeNBT 读取当前 exhaustion，局部 NBT 对象保持读取相互独立。 */
    private static float readExhaustion(EntityPlayerMP player) {
        NBTTagCompound tag = new NBTTagCompound();
        player.getFoodStats().writeNBT(tag);
        return tag.getFloat(EXHAUSTION_NBT_KEY);
    }
}
