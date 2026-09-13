package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 预算档位。
 *
 * <p>deadline：每个 tick 的并行让出预算与 {@code tickBudgetMs} 同源（基线行为）。
 * slice：并行让出使用独立的小预算，主线程不再无条件等到 tick deadline。</p>
 *
 * <p>档位只影响「窗口预算」与主线程等待策略，不改变协作式停止契约：
 * 窗口关闭后仍必须等待所有已开始的分片回到安全边界。</p>
 */
public enum ParallelBudgetMode {

    /** 基线：并行窗口预算 = tickBudgetMs。 */
    DEADLINE("deadline"),
    /** 新档：并行窗口预算 = min(tickBudgetMs, parallelSliceBudgetMs)。 */
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
     * 解析配置 id；null / 未知 / 大小写差异一律回退 {@link #DEFAULT}，不抛异常。
     *
     * @param raw 配置原始字符串
     * @return 可用的档位，绝不返回 null
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
