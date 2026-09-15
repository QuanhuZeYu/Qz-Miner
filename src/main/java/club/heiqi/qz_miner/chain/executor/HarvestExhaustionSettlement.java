package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

/**
 * 连锁/爆破真实破坏成功后的饥饿值消耗覆盖（Issue #242）。
 *
 * <p>需求原文：{@code double addExhaustion = 0.025; // 每次挖掘增加的饥饿值}
 * （dev_docs/技术需求.md §3.1，提交 a3f1f312 移除前）。目标口径（用户裁定 2026-09-15）：
 * <b>玩家按下连锁后经执行器破坏的每个方块，其饥饿值消耗等于
 * {@code general.harvestExhaustionPerBlock} 配置值</b>——默认 0.025 等于原版每方块消耗，
 * {@code 0} 表示完全不消耗，负值表示净回补。</p>
 *
 * <p><b>作用范围</b>：只覆盖经执行器破坏的连锁/爆破增量目标（{@code ChainMode.CHAIN} 与
 * {@code ChainMode.AREA} 全子模式共用 {@link BlockHarvestActionExecutor}）。玩家手动挖掉的起点方块
 * 走原版挖掘路径、不经过本执行器，仍按原版 0.025 结算；INTERACT（右键交互）与 SPECIAL
 * （GT 线缆替换）不是破坏方块，不结算。要让配置覆盖原版单方块挖掘需 mixin 级改动，不在本次范围。</p>
 *
 * <h3>为什么是实测覆盖而不是「补差额」</h3>
 * <p>原版 {@code Block.harvestBlock} 会固定执行 {@code player.addExhaustion(0.025F)}
 * （本地补丁源码 {@code net/minecraft/block/Block.java:1195}），但它不是每次破坏都会走到：</p>
 * <ul>
 *   <li>覆写 {@code harvestBlock} 且在部分分支不调用 super 的方块原版增量为 0——例如手持剪刀收割
 *       <b>下半株</b>且剪切判定成功时，{@code BlockDoublePlant.harvestBlock} 只走自己的掉落分支
 *       （{@code net/minecraft/block/BlockDoublePlant.java:182-188}：仅在「非剪刀 || 上半株
 *       （= {@code func_149887_c} 为真）|| 剪切判定失败」时才调用 super）；</li>
 *   <li>覆写后自行决定消耗量的方块（含第三方模组）增量不一定等于 0.025；</li>
 *   <li>exhaustion 已接近上限 40 时，原版那 0.025 会被 {@code Math.min} 截断。</li>
 * </ul>
 * <p>因此本类不用常量 0.025 估算，而是<b>破坏前后各读一次 exhaustion</b>，以实测增量算追加量：
 * 追加 {@code 配置值 - 实测增量} 后，净结算等于配置值（float 口径下往返偏差不超过 1e-5，
 * 全枚举实测最坏约 3.8e-6），与方块实现路径无关；exhaustion 池接近上限 40 时会被原版
 * {@code Math.min} 截断，见下文边界。
 * 原版消耗只由 {@code ItemInWorldManager.tryHarvestBlock} 在成功移除方块时产生
 * （{@code net/minecraft/server/management/ItemInWorldManager.java:322-325}），
 * 采集与结算都在同一次 {@link BlockHarvestActionExecutor#execute} 内、同一服务端主线程顺序执行，
 * 中间不跨 tick，因此实测差值就是本次破坏的消耗。</p>
 *
 * <h3>0 与负数的语义</h3>
 * <ul>
 *   <li>{@code 0}：追加 {@code -实测增量}，把本次破坏产生的消耗整体抵消，该方块净结算 0。</li>
 *   <li>负值：追加量更负，落到原版 {@code FoodStats.addExhaustion} 上是<b>减少</b> exhaustion 累加池，
 *       即净回补（为后续消耗预存额度）。原版该方法只有 {@code Math.min(值 + 增量, 40.0F)} 上界、
 *       <b>没有下界夹取</b>（{@code net/minecraft/util/FoodStats.java:141-144}），负增量会被如实累加；
 *       {@code foodExhaustionLevel} 变负只表示「满 4 点一档的扣除暂不触发」，
 *       不触碰 {@code foodLevel} 与 {@code foodSaturationLevel} 的其它路径。</li>
 * </ul>
 *
 * <h3>exhaustion 的读取途径</h3>
 * <p>原版 {@code FoodStats} 没有读取 exhaustion 的公开 getter，本类走它公开的序列化口径
 * {@code writeNBT}（{@code net/minecraft/util/FoodStats.java:108-114} 写入
 * {@code foodExhaustionLevel}），不反射私有字段。读取或采集失败时退回常量口径
 * （{@code 配置值 - 0.025}），结算失败只记日志、不影响已经发生的破坏结果。</p>
 *
 * <h3>已知边界（如实登记，未消除）</h3>
 * <ul>
 *   <li><b>触顶</b>：exhaustion 累加封顶 40。基线已接近上限时（例如 39.999），既有的原版消耗与
 *       本类的追加都会被 {@code Math.min} 截断，净消耗可以小于配置值——目标值在该状态下物理不可达，
 *       不是配置未生效；减少方向（{@code 0} 与负值）没有下界，不受影响。</li>
 *   <li><b>常量口径的容差</b>：基类路径的 0.025 经「累加后读回」会带上 float 舍入噪声
 *       （40 附近最大约 4e-6，见 {@link #CONSTANT_BASELINE_TOLERANCE}），落在容差内时按常量
 *       0.025 结算，默认档因此仍精确得到 0 增量。例外只有触顶带（exhaustion 约 39.975 以上，
 *       原版那 0.025 本身已被 {@code Math.min} 吞掉），此时会多走一次同样被截断的追加调用，
 *       可观察结果不变。</li>
 *   <li><b>同 tick 其它消耗</b>：实测差值只包含本次破坏期间产生的消耗；同一 tick 内若还有其它系统
 *       改动 exhaustion，会被一并计入本次覆盖（方向与量级都受配置值约束，不会放大）。</li>
 * </ul>
 *
 * <h3>调用约束与失败语义</h3>
 * <ul>
 *   <li><b>只在服务端主线程</b>：调用方由 {@code ChainExecutionEventBridge} 在
 *       {@code ServerTickEvent.START} 主线程调起；本类另以 {@code worldObj.isRemote} 兜底拒绝客户端实体。</li>
 *   <li><b>只结算真实玩家的真实破坏</b>：只在 {@code tryHarvestBlock} 返回成功后调用；假玩家
 *       （{@link FakePlayer}）不结算。被拒绝（{@code canExecute=false}）、被取消、执行抛异常或
 *       破坏返回 false 的挖矿都到不了这里。</li>
 *   <li><b>创造模式不消耗</b>：原版 {@code tryHarvestBlock} 走 creative 分支不调用
 *       {@code harvestBlock}，且 {@code EntityPlayer.addExhaustion} 对
 *       {@code capabilities.disableDamage} 直接返回（{@code net/minecraft/entity/player/EntityPlayer.java:2137-2146}），
 *       因此即使本类算出并追加了增量也不会产生实际消耗。</li>
 *   <li><b>基线必须先于破坏采集</b>：{@link #captureExhaustion} 与
 *       {@link #settleAfterSuccessfulHarvest} 必须夹住同一次 {@code tryHarvestBlock}；
 *       跨 tick 或跨线程使用基线没有意义。</li>
 *   <li><b>配置域</b>：合法域 {@code [-40, 40]}，由配置层限定
 *       （{@code QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK_MIN/MAX}）；
 *       非有限值（NaN / ±Inf）兜底视为不追加，覆盖 {@code Config} 静态字段被绕开配置校验直接改写的情形。</li>
 * </ul>
 */
public final class HarvestExhaustionSettlement {

    /**
     * 原版 {@code Block.harvestBlock} 对每次实际收获固定追加的 exhaustion。
     *
     * <p>本类只在「实测增量就是这一次固定消耗」时用它作为基线口径，见
     * {@link #CONSTANT_BASELINE_TOLERANCE}。</p>
     */
    public static final float VANILLA_HARVEST_EXHAUSTION = 0.025F;

    /**
     * 判定「实测增量等于原版常量消耗」的容差。
     *
     * <p>原版常量经 {@code 累加 → 读回相减} 会带 float 舍入噪声：exhaustion 在 40 附近时 ulp 约
     * 3.8e-6，实测往返误差最大同量级；容差取 1e-4 足以吸收噪声，又远小于任何可感知的配置差异
     * （4 点 exhaustion 才触发 1 点饱食度变化）。落在容差内按常量结算，默认档才能精确得到 0 增量。</p>
     */
    static final float CONSTANT_BASELINE_TOLERANCE = 1.0E-4F;

    /**
     * 原版 {@code FoodStats} 序列化 exhaustion 的 NBT key（读取路径与回归测试共用的唯一真源）。
     *
     * <p>key 写错不会读报错，只会让 {@code getFloat} 一律返回 0.0F，使覆盖整体偏移一个原版常量，
     * 因此由 {@code HarvestExhaustionSettlementTest} 与 {@code new FoodStats().writeNBT(...)} 对钉。</p>
     */
    static final String EXHAUSTION_NBT_KEY = "foodExhaustionLevel";

    private HarvestExhaustionSettlement() {
    }

    /**
     * 破坏前采集 exhaustion 基线。
     *
     * <p>采集失败（读取异常或实体不可用）返回 {@link Float#NaN}，结算随后退回常量口径，
     * 不影响破坏本身。</p>
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
     * 计算需要在原版本次实际消耗之上追加的净增量。
     *
     * <p>契约：原版实测增量加上本方法的返回值等于配置值（float 口径往返偏差不超过 1e-5）。</p>
     * <ul>
     *   <li>配置值非有限（NaN / ±Inf）→ 返回 {@code 0}（不追加）；</li>
     *   <li>实测增量落在原版常量容差内（含读取失败时的 NaN）→ 按常量 0.025 结算，
     *       默认档精确得到 {@code 0}，即不调用 {@code addExhaustion}；</li>
     *   <li>其余情况按实测值结算，因此 {@code 0} 档抵消原版消耗、负值档净回补，
     *       覆写 {@code harvestBlock} 的方块也能被覆盖成配置值。</li>
     * </ul>
     *
     * @param configured 配置的每方块净消耗（float 口径）
     * @param observed   本次破坏原版实际产生的 exhaustion 增量（NaN 表示不可用）
     * @return 需追加给 {@code addExhaustion} 的增量；{@code 0} 表示无需调用
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
        // 负增量（配置 0 或负数）如实下传：原版 FoodStats.addExhaustion 只封顶 40.0、不夹取下界。
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

    /**
     * 读取当前 exhaustion 累加值（原版公开的序列化口径，不反射私有字段）。
     *
     * <p>每次结算调用两次（破坏前基线 + 破坏后读数），各构造一个临时 {@code NBTTagCompound}
     * （4 个 tag），开销在每方块微秒以下量级；不缓存实例、不改用私有字段反射，是为了不引入
     * 可变共享状态与 SRG 名依赖。</p>
     */
    private static float readExhaustion(EntityPlayerMP player) {
        NBTTagCompound tag = new NBTTagCompound();
        player.getFoodStats().writeNBT(tag);
        return tag.getFloat(EXHAUSTION_NBT_KEY);
    }
}
