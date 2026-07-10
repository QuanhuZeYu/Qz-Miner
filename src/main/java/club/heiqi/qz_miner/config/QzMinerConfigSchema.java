package club.heiqi.qz_miner.config;

import club.heiqi.config.schema.ConfigSchema;

/**
 * Qz-Miner 配置 Schema（server-safe，零 MC UI / LWJGL 依赖）。
 *
 * <p>字段与历史 Forge {@code Configuration} 键名对齐，分类仍为 {@code general} / {@code client}。
 * 全路径形如 {@code general.chainRadius}、{@code client.clientPreviewAlphaStartValue}。</p>
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
                    .string("greeting").defaultValue("Hello World")
                        .label("greeting")
                        .helper("How shall I greet?")
                        .build()
                    .number("chainRadius").defaultValue(Double.valueOf(8.0)).range(1, Integer.MAX_VALUE)
                        .label("chainRadius")
                        .helper("连锁范围半径（方盒子半径，搜索顺序仍为中心扩散）")
                        .build()
                    .number("chainMaxBlocks").defaultValue(Double.valueOf(1024.0)).range(1, Integer.MAX_VALUE)
                        .label("chainMaxBlocks")
                        .helper("最大连锁数量；超大值（如 >65536）会显著拖慢规划并加剧 abort 频率，建议根据机器性能调整")
                        .build()
                    .number("chainLoggingShellLayers").defaultValue(Double.valueOf(1.0)).range(1, Integer.MAX_VALUE)
                        .label("chainLoggingShellLayers")
                        .helper("CHAIN 伐木子模式每次向外扩展的壳层数；1 表示围绕当前原木检查一圈 3x3x3 邻域")
                        .build()
                    .number("maxBreakPerTick").defaultValue(Double.valueOf(64.0)).range(1, Integer.MAX_VALUE)
                        .label("maxBreakPerTick")
                        .helper("每 Tick 最多执行的连锁挖掘数量；64 在大范围连锁首 tick 可能逼近 50ms 预算，卡顿明显时调低")
                        .build()
                    .number("cableReplaceMaxPerTick").defaultValue(Double.valueOf(1024.0)).range(1, Integer.MAX_VALUE)
                        .label("cableReplaceMaxPerTick")
                        .helper("GT 线缆连锁替换单 tick 原子上限；超过此值的链路预校验失败不放行（防电压不匹配爆炸）；默认 1024")
                        .build()
                    .number("chainWatchdogTimeoutTicks").defaultValue(Double.valueOf(50.0)).range(20, Integer.MAX_VALUE)
                        .label("chainWatchdogTimeoutTicks")
                        .helper("连锁看门狗超时阈值（tick）：玩家连锁 N tick 无真实工作推进则协作式回 IDLE（异常兜底，默认 50 ≈ 2.5 秒，B 方案落地后纯做卡死回收速度旋钮）")
                        .build()
                    .number("parallelTickMinDurationMs").defaultValue(Double.valueOf(15.0)).range(10, Integer.MAX_VALUE)
                        .label("parallelTickMinDurationMs")
                        .helper("同步执行器每刻最短执行时间（毫秒），默认 15，最低 10")
                        .build()
                    .number("parallelTickServerWorkBudgetUnits").defaultValue(Double.valueOf(640.0)).range(1, Integer.MAX_VALUE)
                        .label("parallelTickServerWorkBudgetUnits")
                        .helper("服务端并行 Tick 任务单个分片的工作预算单位；越大推进越快但单片耗时可能更高，默认 640")
                        .build()
                    .bool("enableUnlimitedOreFortune").defaultValue(Boolean.FALSE)
                        .label("enableUnlimitedOreFortune")
                        .helper("是否解除 GT/BW/GT++ 普通矿的 3 级时运上限；关闭时保持原版逻辑")
                        .build()
                    .bool("enableFortuneForPlacedOre").defaultValue(Boolean.FALSE)
                        .label("enableFortuneForPlacedOre")
                        .helper("是否允许非自然生成的 GT/BW 矿石也享受时运；关闭时保持原版仅自然矿可时运")
                        .build()
                .endSection()
                .section("client")
                    .title("Client")
                    .bool("clientEnablePreviewRender").defaultValue(Boolean.TRUE)
                        .label("clientEnablePreviewRender")
                        .helper("是否启用客户端连锁预览计算与渲染；关闭后将不再执行任何预览相关渲染操作")
                        .build()
                    .number("parallelTickClientWorkBudgetUnits").defaultValue(Double.valueOf(640.0)).range(1, Integer.MAX_VALUE)
                        .label("parallelTickClientWorkBudgetUnits")
                        .helper("客户端并行 Tick 任务单个分片的工作预算单位；越大预览推进越快但单片耗时可能更高，默认 640")
                        .build()
                    .number("clientPreviewMaxRadius").defaultValue(Double.valueOf(16.0)).range(1, Integer.MAX_VALUE)
                        .label("clientPreviewMaxRadius")
                        .helper("客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值，避免大范围预览渲染导致卡顿")
                        .build()
                    .number("clientPreviewMaxTargets").defaultValue(Double.valueOf(1024.0)).range(1, Integer.MAX_VALUE)
                        .label("clientPreviewMaxTargets")
                        .helper("客户端最大预览目标数量；实际预览数量取该值与服务端 chainMaxBlocks 的较小值，避免大范围预览导致卡顿")
                        .build()
                    .number("clientPreviewAlphaFadeStartRadius").defaultValue(Double.valueOf(2.0)).range(0, Double.MAX_VALUE)
                        .label("clientPreviewAlphaFadeStartRadius")
                        .helper("客户端预览透明度开始衰减的距离半径；在此半径内保持最高透明度")
                        .build()
                    .number("clientPreviewAlphaFadeEndRadius").defaultValue(Double.valueOf(6.0)).range(0, Double.MAX_VALUE)
                        .label("clientPreviewAlphaFadeEndRadius")
                        .helper("客户端预览透明度衰减到最低值的距离半径；超过该半径后保持最低透明度")
                        .build()
                    .number("clientPreviewAlphaStartValue").defaultValue(Double.valueOf(0.78)).range(0, 1)
                        .label("clientPreviewAlphaStartValue")
                        .helper("客户端预览透明度的起始值；距离不超过衰减起点时使用该透明度")
                        .build()
                    .number("clientPreviewAlphaEndValue").defaultValue(Double.valueOf(0.15)).range(0, 1)
                        .label("clientPreviewAlphaEndValue")
                        .helper("客户端预览透明度的结束值；距离超过衰减终点时使用该透明度")
                        .build()
                .endSection()
                .build();
    }
}
