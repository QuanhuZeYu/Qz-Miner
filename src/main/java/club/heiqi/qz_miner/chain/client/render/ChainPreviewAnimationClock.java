package club.heiqi.qz_miner.chain.client.render;

/**
 * 标量动画时钟，按代、flow/wave 档位及时长重置起点，返回有界线性完成度。
 * off 或非法档位立即完成，生命周期入口 reset。
 * 时钟只提供 animationU，shader 按逐顶点 appearOrder 生长，索引段波表保留为空。
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
    private String activeModeId = "";
    private int activeDurationMs;
    private float animationU = COMPLETE;

    /**
     * 推进到本帧并返回动画完成度。
     *
     * @param generation       当前预览代（变化即重置计时）
     * @param animationModeId  动画档位稳定 id：off / flow / wave（null 或未知按 off）
     * @param durationMs       单代动画时长（毫秒；&lt;= 0 立即完成；运行中变化按新一轮处理）
     * @param nowNanos         {@link System#nanoTime()} 采样值
     * @return 完成度 [0,1]；off 或 durationMs &lt;= 0 时恒 {@link #COMPLETE}
     */
    public float advance(int generation, String animationModeId, int durationMs, long nowNanos) {
        boolean shouldAnimate = isAnimationMode(animationModeId) && durationMs > 0;
        if (!shouldAnimate) {
            this.generation = generation;
            this.animating = false;
            this.activeModeId = "";
            this.activeDurationMs = 0;
            this.durationNanos = 0L;
            this.startNanos = 0L;
            this.animationU = COMPLETE;
            return COMPLETE;
        }
        boolean timelineChanged = animating
            && (activeDurationMs != durationMs || !activeModeId.equals(animationModeId));
        if (!animating || this.generation != generation || timelineChanged) {
            this.generation = generation;
            this.animating = true;
            this.activeModeId = animationModeId;
            this.activeDurationMs = durationMs;
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
        activeModeId = "";
        activeDurationMs = 0;
        animationU = COMPLETE;
    }

    private static boolean isAnimationMode(String animationModeId) {
        return MODE_FLOW.equals(animationModeId) || MODE_WAVE.equals(animationModeId);
    }
}
