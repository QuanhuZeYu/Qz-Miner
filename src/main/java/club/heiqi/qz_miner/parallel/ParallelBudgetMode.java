package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 预算档位。
 *
 * <p>deadline：每个 tick 的并行让出预算与 {@code tickBudgetMs} 同源（基线行为）。
 * slice：客户端并行窗口取独立切片预算，已调度且空闲时主线程提前返回。</p>
 *
 * <p>档位决定窗口预算与主线程等待策略；
 * 窗口关闭后统一等待已开始的分片回到安全边界。</p>
 */
public enum ParallelBudgetMode {

    /** 基线：并行窗口预算 = tickBudgetMs。 */
    DEADLINE("deadline"),
    /** 切片档：客户端并行窗口预算 = min(tickBudgetMs, parallelSliceBudgetMs)。 */
    SLICE("slice");

    /** 未知或缺失配置时的回退档位（等于基线行为）。 */
    public static final ParallelBudgetMode DEFAULT = DEADLINE;

    private final String id;

    ParallelBudgetMode(String id) {
        this.id = id;
    }

    /** @return 配置中使用的稳定 id。 */
    public String id() {
        return id;
    }

    /**
     * 去除首尾空白后忽略大小写解析配置 id；null 或未知值回退 {@link #DEFAULT}。
     *
     * @param raw 配置原始字符串
     * @return 解析到的档位或默认档位
     */
    public static ParallelBudgetMode fromId(String raw) {
        if (raw != null) {
            String trimmed = raw.trim();
            for (ParallelBudgetMode mode : values()) {
                if (mode.id.equalsIgnoreCase(trimmed)) {
                    return mode;
                }
            }
        }
        return DEFAULT;
    }

    /** @return 全部可选 id，供配置 schema 与文档复用。 */
    public static String[] ids() {
        ParallelBudgetMode[] modes = values();
        String[] ids = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            ids[i] = modes[i].id;
        }
        return ids;
    }
}
