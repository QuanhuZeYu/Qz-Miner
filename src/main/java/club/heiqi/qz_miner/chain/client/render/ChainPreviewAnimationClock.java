package club.heiqi.qz_miner.chain.client.render;

/**
 * 预览动画时钟：有界、零 per-frame 分配的标量状态机。
 *
 * <p>只依赖 {@link System#nanoTime()} 与「代级起点」：generation 变化即重新计时；
 * animation=off（或 null / 未知 id）恒返回 {@link #COMPLETE}；flow / wave 按 durationMs
 * 线性推进；掉帧只跳进不失控（完成度钳制到 [0,1]）；时钟回退 / 同帧重入不产生负值。</p>
 *
 * <p>生命周期：预览结束、世界切换、后端热切换由调用方调用 {@link #reset()} 清理，
 * 不跨代残留；除标量字段外不持有任何缓冲。</p>
 *
 * <p><b>本时钟只产出 {@code animationU} 一个值</b>：索引段波表未启用（索引顺序 != appearOrder
 * 顺序，junction 相先写），逐波生长由 shader 按 aAux 逐顶点比较实现；plan 的
 * {@code waveEnds} 恒为 null / {@code waveVisible} 恒为 0，保留字段仅为未来索引有序场景。</p>
 *
 * <p>分配口径（T13-D4）：时钟自身只有标量字段、advance 零分配；动画期间 renderer 每帧会新建
 * 1 个不可变 {@link ChainPreviewDrawPlan.Visuals} 快照与 1 个 DrawPlan（已知分配点）——
 * 保持「plan 纯数据 / 不可变」契约，不做可变复用；动画 off / 完成后该路径零分配。</p>
 */
public final class ChainPreviewAnimationClock {

    /** 动画完成度：>= 1 视为全部可见。 */
    public static final float COMPLETE = 1.0F;

    /** 动画起点。 */
    public static final float START = 0.0F;

    private static final String MODE_FLOW = "flow";
    private static final String MODE_WAVE = "wave";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private int generation = Integer.MIN_VALUE;
    private long startNanos;
    private long durationNanos;
    private boolean animating;
    private float animationU = COMPLETE;

    /**
     * 推进到本帧并返回动画完成度。
     *
     * @param generation       当前预览代（变化即重置计时）
     * @param animationModeId  动画档位稳定 id：off / flow / wave（null 或未知按 off）
     * @param durationMs       单代动画时长（毫秒；<= 0 立即完成）
     * @param nowNanos         {@link System#nanoTime()} 采样值
     * @return 完成度 [0,1]；off 或 durationMs &lt;= 0 时恒 {@link #COMPLETE}
     */
    public float advance(int generation, String animationModeId, int durationMs, long nowNanos) {
        boolean shouldAnimate = isAnimationMode(animationModeId) && durationMs > 0;
        if (!shouldAnimate) {
            this.generation = generation;
            this.animating = false;
            this.durationNanos = 0L;
            this.startNanos = 0L;
            this.animationU = COMPLETE;
            return COMPLETE;
        }
        if (!animating || this.generation != generation) {
            this.generation = generation;
            this.animating = true;
            this.durationNanos = (long) durationMs * NANOS_PER_MILLI;
            this.startNanos = nowNanos;
            this.animationU = START;
            return START;
        }
        long elapsed = nowNanos - startNanos;
        if (elapsed <= 0L) {
            // 时钟回退 / 同帧重入：保持在起点，不产生负进度
            this.animationU = START;
            return START;
        }
        double progress = (double) elapsed / (double) durationNanos;
        this.animationU = progress >= 1.0D ? COMPLETE : (float) progress;
        return this.animationU;
    }

    /** @return 最近一次 {@link #advance} 的完成度 */
    public float getAnimationU() {
        return animationU;
    }

    /** @return 动画档位是否生效（flow / wave 且 duration &gt; 0）；与是否已完成无关 */
    public boolean isAnimating() {
        return animating;
    }

    /** @return 最近一次 {@link #advance} 绑定的代；未绑定为 {@link Integer#MIN_VALUE} */
    public int getGeneration() {
        return generation;
    }

    /** 清理时钟状态（预览结束 / 世界切换 / 后端热切换）。 */
    public void reset() {
        generation = Integer.MIN_VALUE;
        startNanos = 0L;
        durationNanos = 0L;
        animating = false;
        animationU = COMPLETE;
    }

    private static boolean isAnimationMode(String animationModeId) {
        return MODE_FLOW.equals(animationModeId) || MODE_WAVE.equals(animationModeId);
    }
}
