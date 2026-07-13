package club.heiqi.qz_miner.config;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.Values;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;

/**
 * Qz-Miner 配置 Schema（server-safe，零 MC UI / LWJGL 依赖）。
 *
 * <p>默认值统一取自 {@link QzMinerConfigDefaults}。</p>
 */
public final class QzMinerConfigSchema {

    private QzMinerConfigSchema() {
    }

    /**
     * 构建不可变配置 Schema。
     *
     * @return ConfigSchema
     */
    public static ConfigSchema create() {
        return ConfigSchema.builder("qz_miner")
                .title("Qz Miner 配置")
                .section("general")
                    .title("General")
                    .string("greeting").defaultValue(QzMinerConfigDefaults.GREETING)
                        .label("greeting")
                        .helper("How shall I greet?")
                        .build()
                    .number("chainRadius")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS))
                        .range(1, Integer.MAX_VALUE)
                        .label("chainRadius")
                        .helper("连锁范围半径（方盒子半径，搜索顺序仍为中心扩散）")
                        .build()
                    .number("chainMaxBlocks")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CHAIN_MAX_BLOCKS))
                        .range(1, Integer.MAX_VALUE)
                        .label("chainMaxBlocks")
                        .helper("最大连锁数量；超大值（如 >65536）会显著拖慢规划并加剧 abort 频率，建议根据机器性能调整")
                        .build()
                    .number("chainLoggingShellLayers")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS))
                        .range(1, Integer.MAX_VALUE)
                        .label("chainLoggingShellLayers")
                        .helper("CHAIN 伐木子模式每次向外扩展的壳层数；1 表示围绕当前原木检查一圈 3x3x3 邻域")
                        .build()
                    .number("maxBreakPerTick")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.MAX_BREAK_PER_TICK))
                        .range(1, Integer.MAX_VALUE)
                        .label("maxBreakPerTick")
                        .helper("每 Tick 最多执行的连锁挖掘数量；64 在大范围连锁首 tick 可能逼近 50ms 预算，卡顿明显时调低")
                        .build()
                    .number("cableReplaceMaxPerTick")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK))
                        .range(1, Integer.MAX_VALUE)
                        .label("cableReplaceMaxPerTick")
                        .helper("GT 线缆连锁替换单 tick 原子上限；超过此值的链路预校验失败不放行（防电压不匹配爆炸）；默认 1024")
                        .build()
                    .number("chainWatchdogTimeoutTicks")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS))
                        .range(20, Integer.MAX_VALUE)
                        .label("chainWatchdogTimeoutTicks")
                        .helper("连锁看门狗超时阈值（tick）：玩家连锁 N tick 无真实工作推进则协作式回 IDLE（异常兜底，默认 50 ≈ 2.5 秒）")
                        .build()
                    .number("parallelTickMinDurationMs")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS))
                        .range(10, Integer.MAX_VALUE)
                        .label("parallelTickMinDurationMs")
                        .helper("同步执行器每刻最短执行时间（毫秒），默认 15，最低 10")
                        .build()
                    .number("parallelTickServerWorkBudgetUnits")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS))
                        .range(1, Integer.MAX_VALUE)
                        .label("parallelTickServerWorkBudgetUnits")
                        .helper("服务端并行 Tick 任务单个分片的工作预算单位；越大推进越快但单片耗时可能更高，默认 640")
                        .build()
                    .bool("enableUnlimitedOreFortune")
                        .defaultValue(Boolean.valueOf(QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE))
                        .label("enableUnlimitedOreFortune")
                        .helper("是否解除 GT/BW/GT++ 普通矿的 3 级时运上限；关闭时保持原版逻辑")
                        .build()
                    .bool("enableFortuneForPlacedOre")
                        .defaultValue(Boolean.valueOf(QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE))
                        .label("enableFortuneForPlacedOre")
                        .helper("是否允许非自然生成的 GT/BW 矿石也享受时运；关闭时保持原版仅自然矿可时运")
                        .build()
                .endSection()
                .section("client")
                    .title("Client")
                    .structuredList("autoToolSelection", Values.objectWithIdentity("id",
                            Values.member("id", Values.choice("default"), "配置标识", "固定配置对象的稳定标识"),
                            Values.member("enabled", Values.bool(), "启用", "是否启用自动工具预选"),
                            Values.member("searchScope", Values.choice("inventory"), "搜索范围", "选择候选工具的搜索位置"),
                            Values.member("restoreOriginal", Values.bool(), "恢复原工具", "目标结束后是否切回原工具"),
                            Values.member("enchantmentPolicy", Values.choice("preserve_current"), "附魔策略", "选择工具时如何处理当前附魔偏好"),
                            Values.member("minimumRemainingDurability", Values.number(), "最低剩余耐久", "候选工具必须保留的最低耐久值"),
                            Values.member("targetStableTicks", Values.number(), "目标稳定 Tick", "目标持续稳定多少 Tick 后才切换工具"),
                            Values.member("emptyTargetGraceTicks", Values.number(), "空目标宽限 Tick", "目标暂时为空时等待多少 Tick 再恢复工具")))
                        .defaultValue(QzMinerConfigDefaults.autoToolSelection())
                        .label("自动工具预选")
                        .helper("自动工具预选策略（固定单配置对象）")
                        .build()
                    .bool("clientEnablePreviewRender")
                        .defaultValue(Boolean.valueOf(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER))
                        .label("clientEnablePreviewRender")
                        .helper("是否启用客户端连锁预览计算与渲染；关闭后将不再执行任何预览相关渲染操作")
                        .build()
                    .number("parallelTickClientWorkBudgetUnits")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS))
                        .range(1, Integer.MAX_VALUE)
                        .label("parallelTickClientWorkBudgetUnits")
                        .helper("客户端并行 Tick 任务单个分片的工作预算单位；越大预览推进越快但单片耗时可能更高，默认 640")
                        .build()
                    .number("clientPreviewMaxRadius")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS))
                        .range(1, Integer.MAX_VALUE)
                        .label("clientPreviewMaxRadius")
                        .helper("客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值")
                        .build()
                    .number("clientPreviewMaxTargets")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS))
                        .range(1, Integer.MAX_VALUE)
                        .label("clientPreviewMaxTargets")
                        .helper("客户端最大预览目标数量；实际预览数量取该值与服务端 chainMaxBlocks 的较小值")
                        .build()
                    .number("clientPreviewAlphaFadeStartRadius")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS))
                        .range(0, Double.MAX_VALUE)
                        .label("clientPreviewAlphaFadeStartRadius")
                        .helper("客户端预览透明度开始衰减的距离半径")
                        .build()
                    .number("clientPreviewAlphaFadeEndRadius")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS))
                        .range(0, Double.MAX_VALUE)
                        .label("clientPreviewAlphaFadeEndRadius")
                        .helper("客户端预览透明度衰减到最低值的距离半径")
                        .build()
                    .number("clientPreviewAlphaStartValue")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE))
                        .range(0, 1)
                        .label("clientPreviewAlphaStartValue")
                        .helper("客户端预览透明度的起始值")
                        .build()
                    .number("clientPreviewAlphaEndValue")
                        .defaultValue(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE))
                        .range(0, 1)
                        .label("clientPreviewAlphaEndValue")
                        .helper("客户端预览透明度的结束值")
                        .build()
                    .structuredList("objectGroups", Values.objectWithIdentity(
                            "id",
                            Values.member("id", Values.string()),
                            Values.member("modes", Values.list(Values.choice(ObjectGroupMode.ids())),
                                    java.util.Collections.<String>emptyList()),
                            Values.member("members", Values.widget(Values.list(Values.string()),
                                    Values.searchPicker("qz_miner:block-selector", 64)))))
                        .defaultValue(QzMinerConfigDefaults.objectGroups())
                        .label("已配置方块规则")
                        .helper("每组用组标识区分，选择适用模式，并配置组内包含的方块规则")
                        .build()
                .endSection()
                .build();
    }
}
