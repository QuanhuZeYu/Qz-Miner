package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;

/**
 * 连锁/爆破真实破坏成功后的饥饿值消耗结算（Issue #242）。
 *
 * <p>需求原文：{@code double addExhaustion = 0.025; // 每次挖掘增加的饥饿值}
 * （dev_docs/技术需求.md §3.1，提交 a3f1f312 移除前）。本类把该配置接到当前架构的
 * <b>真实破坏成功点</b>：{@link BlockHarvestActionExecutor#execute} 返回 {@code true} 之后，
 * 不另起遍历、事件循环或独立 tick 源。</p>
 *
 * <p><b>作用范围</b>：只覆盖经执行器破坏的连锁/爆破增量目标（{@code ChainMode.CHAIN} 与
 * {@code ChainMode.AREA} 全子模式共用 {@link BlockHarvestActionExecutor}）。玩家手动挖掉的起点方块
 * 走原版挖掘路径、不经过本执行器，仍按原版 0.025 结算；INTERACT（右键交互）与 SPECIAL
 * （GT 线缆替换）不是破坏方块，不结算。要让配置覆盖原版单方块挖掘需 mixin 级改动，不在本次范围。</p>
 *
 * <h3>为什么只补差额（{@code 配置值 - 原版 0.025}）</h3>
 * <p>本仓的真实破坏复用原版 {@code ItemInWorldManager.tryHarvestBlock}；它在生存模式成功移除方块后调用
 * {@code Block.harvestBlock}，而后者内部已经固定执行 {@code player.addExhaustion(0.025F)}
 * （证据：GTNH 开发环境 {@code build/rfg/mcp_patched_minecraft-sources.jar} 内
 * {@code net/minecraft/block/Block.java:1195} 与
 * {@code net/minecraft/server/management/ItemInWorldManager.java:310,323-325}）。
 * 若此处再无条件 {@code addExhaustion(配置值)}，默认 0.025 会变成每方块 0.05：既违背
 * 「0.025 = 每次挖掘增加的饥饿值」，也是对同一方块重复结算。只补差额后，
 * 走到基类 {@code harvestBlock} 的方块净结算等于配置值。</p>
 *
 * <p>门控一致：{@code ItemInWorldManager} 以 {@code block.canHarvestBlock(player, meta)} 决定是否调用
 * {@code harvestBlock}，而本仓执行期准入 {@code ChainHarvestRules.evaluateExecutionHarvest} 用的是同一个方法，
 * 因此 {@code canExecute} 放行的目标在生存模式下必然经过 {@code harvestBlock}。创造模式两边都不消耗：
 * 原版走 {@code isCreative()} 分支（不调用 {@code harvestBlock}），且
 * {@code EntityPlayer.addExhaustion} 对 {@code capabilities.disableDamage} 直接返回。</p>
 *
 * <h3>偏差方向与量级（已知边界）</h3>
 * <p>门控一致不等于每次都能补平：0.025 只在<b>真正执行到基类 {@code Block.harvestBlock}</b> 时产生，
 * 而 {@code tryHarvestBlock} 只要求 {@code canHarvestBlock} 为真就返回 true，于是有两个方向的偏差：</p>
 * <ul>
 *   <li><b>少算</b>：覆写 {@code harvestBlock} 且该分支不调用 super 的方块不产生 0.025，
 *       净结算 = 配置值 - 0.025（默认档净 0，即比原版少扣 0.025/方块）。已核实的例子是剪刀收割双植：
 *       {@code BlockDoublePlant.harvestBlock} 的上半株分支只走自己的掉落逻辑、不调用 super
 *       （本地补丁源码 {@code net/minecraft/block/BlockDoublePlant.java:182-188}）。</li>
 *   <li><b>多算</b>：覆写并自行追加 exhaustion 的方块净结算 = 配置值 + 额外量。1.7.10 补丁基线里
 *       {@code BlockIce} 自加等量 0.025（结果仍等于配置值），第三方模组可以不同。</li>
 * </ul>
 * <p>两个方向都只有 0.025/方块 的量级，且默认档下基类路径差额为 0、行为与改动前逐值一致。
 * 之所以接受而不彻底消除：彻底消除必须改原版 {@code harvestBlock} 的常量（mixin 级改动，
 * 会连带改变原版单方块挖掘的消耗），超出本 issue 的写范围，也违背默认档「不改变既有手感」的约束。</p>
 *
 * <h3>调用约束与失败语义</h3>
 * <ul>
 *   <li><b>只在服务端主线程</b>：调用方由 {@code ChainExecutionEventBridge} 在
 *       {@code ServerTickEvent.START} 主线程调起；本类另以 {@code worldObj.isRemote} 兜底拒绝客户端实体。</li>
 *   <li><b>只结算真实玩家的真实破坏</b>：只在 {@code tryHarvestBlock} 返回成功后调用；假玩家
 *       （{@link FakePlayer}）不结算。被拒绝（{@code canExecute=false}）、被取消、执行抛异常或
 *       破坏返回 false 的挖矿都到不了这里。</li>
 *   <li><b>数值边界</b>：{@code Config.harvestExhaustionPerBlock <= 0}（含 NaN）视为关闭，不调用
 *       {@code addExhaustion}；等于 {@link #VANILLA_HARVEST_EXHAUSTION} 时差额为 0，同样不调用，
 *       默认档因此逐值保持原版行为且零额外开销。{@code (0, 0.025)} 档差额为负，也不调用——
 *       原版 {@code FoodStats.addExhaustion} 只有 40.0 上界、没有下界夹取，传入负值会破坏
 *       exhaustion 非负不变量；这些档位因此等效原版基线，无法进一步降低单方块消耗。</li>
 *   <li><b>结算失败不改写破坏结果</b>：本方法自行吸收并记录异常——饥饿值结算出错不得把已经发生的
 *       真实破坏回报成失败。</li>
 * </ul>
 */
public final class HarvestExhaustionSettlement {

    /**
     * 原版 {@code Block.harvestBlock} 对每次实际收获固定追加的 exhaustion。
     *
     * <p>见类注释的源码证据；本仓以「补差额」而非 mixin 覆盖原版常量，是为了把改动收在
     * 执行抽象内、不改变原版单方块挖掘的既有行为。</p>
     */
    public static final float VANILLA_HARVEST_EXHAUSTION = 0.025F;

    private HarvestExhaustionSettlement() {
    }

    /**
     * 在真实破坏成功后结算本次饥饿值消耗。
     *
     * @param player 触发本次破坏的在线服务端玩家（服务端主线程调用）
     */
    public static void settleAfterSuccessfulHarvest(EntityPlayerMP player) {
        try {
            settle(player);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[HarvestExhaustionSettlement] Failed to settle exhaustion for player {}",
                    player == null ? null : player.getUniqueID(), failure);
        }
    }

    private static void settle(EntityPlayerMP player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) {
            return;
        }
        if (player instanceof FakePlayer) {
            return;
        }
        // 结算一律走 addExhaustion 的 float 口径：配置值先落 float 再相减，默认 0.025 才与
        // VANILLA_HARVEST_EXHAUSTION 逐位相等、差额恰好为 0；若在 double 口径相减再转型，
        // 会留下 -3.7e-10 的浮点噪声，导致默认档也走一次 addExhaustion。
        float configured = (float) Config.harvestExhaustionPerBlock;
        if (!(configured > 0.0F)) {
            return;
        }
        float delta = configured - VANILLA_HARVEST_EXHAUSTION;
        if (!(delta > 0.0F)) {
            // 低于原版 0.025 的档位等效原版基线：原版 FoodStats.addExhaustion 只封顶 40.0、
            // 不夹取下界，负差额会破坏 exhaustion 非负不变量，因此一律不调用。
            return;
        }
        player.addExhaustion(delta);
    }
}
