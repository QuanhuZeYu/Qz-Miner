package club.heiqi.qz_miner.chain.client.render;

/**
 * 预览淡入 / 淡出与 retiring 生命周期状态机（纯标量字段、零 per-frame 分配）。
 *
 * <p>门控：只在 animation ∈ {flow, wave} 且 durationMs &gt; 0 时启用；off / duration &lt;= 0 时
 * 与历史一致——激活恒 alpha = 1，结束立即达到 {@link Phase#IDLE}（无 retiring、无过渡）。</p>
 *
 * <p>相位：{@code IDLE → (激活且启用) FADING_IN → ACTIVE → (取消且启用) RETIRING → IDLE}。
 * 激活时若处于 RETIRING（新 generation / 新预览代），立即抢占：旧 retiring 状态丢弃并重新淡入。</p>
 *
 * <p>有界：时长钳制到 [0, {@link #MAX_FADE_MS}]；掉帧只跳进（alpha 钳制 [0,1]）；
 * 时钟回退不产生负值；生命周期清理用 {@link #reset()}。</p>
 */
public final class ChainPreviewFadeController {

    /** 生命周期相位。 */
    public enum Phase {
        /** 无预览（或未启用过渡）。 */
        IDLE,
        /** 出现淡入中。 */
        FADING_IN,
        /** 完全可见。 */
        ACTIVE,
        /** 预览结束后的保留淡出中。 */
        RETIRING
    }

    /** 单次淡入 / 淡出时长硬上限（毫秒），与 §E animationDurationMs 上限同源。 */
    public static final int MAX_FADE_MS = 2000;

    private static final String MODE_FLOW = "flow";
    private static final String MODE_WAVE = "wave";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private Phase phase = Phase.IDLE;
    private int generation = Integer.MIN_VALUE;
    private long phaseStartNanos;
    private long fadeNanos;
    private float retireStartAlpha = 1.0F;
    private float alpha = 0.0F;

    /**
     * 淡入淡出门控：animation ∈ {flow, wave} 且 durationMs &gt; 0。
     *
     * @param animationModeId 动画档位稳定 id
     * @param durationMs      过渡时长（毫秒）
     * @return 是否启用淡入 / 淡出
     */
    public static boolean isFadeEnabled(String animationModeId, int durationMs) {
        if (durationMs <= 0) {
            return false;
        }
        return MODE_FLOW.equals(animationModeId) || MODE_WAVE.equals(animationModeId);
    }

    /**
     * 推进到本帧。
     *
     * @param active      预览是否激活
     * @param generation  当前代（激活时传入；未激活传保留网格的代）
     * @param fadeEnabled {@link #isFadeEnabled} 结果
     * @param durationMs  过渡时长（毫秒）
     * @param nowNanos    {@link System#nanoTime()} 采样值
     * @return 本帧不透明度乘子 [0,1]；激活且未启用过渡时为 1，{@link Phase#IDLE} 时恒为 0
     *
     * <p>清空依据是相位（{@link #isIdle()}），不是 alpha：IDLE 下 alpha 恒 0 只是防御语义，
     * 避免未来有人按 {@code alpha > 0} 判断绘制时出现一整帧残留全亮。</p>
     */
    public float advance(boolean active, int generation, boolean fadeEnabled, int durationMs, long nowNanos) {
        if (!fadeEnabled || durationMs <= 0) {
            this.phase = active ? Phase.ACTIVE : Phase.IDLE;
            this.generation = active ? generation : Integer.MIN_VALUE;
            this.phaseStartNanos = 0L;
            this.fadeNanos = 0L;
            this.retireStartAlpha = 1.0F;
            this.alpha = active ? 1.0F : 0.0F;
            return this.alpha;
        }

        long spanNanos = (long) Math.min(durationMs, MAX_FADE_MS) * NANOS_PER_MILLI;
        if (active) {
            if (phase == Phase.IDLE || phase == Phase.RETIRING || this.generation != generation) {
                this.phase = Phase.FADING_IN;
                this.generation = generation;
                this.phaseStartNanos = nowNanos;
                this.fadeNanos = spanNanos;
                this.retireStartAlpha = 1.0F;
                this.alpha = 0.0F;
                return 0.0F;
            }
            if (phase == Phase.FADING_IN) {
                float progress = progress(nowNanos, phaseStartNanos, fadeNanos);
                this.alpha = progress >= 1.0F ? 1.0F : progress;
                if (this.alpha >= 1.0F) {
                    this.phase = Phase.ACTIVE;
                }
            } else {
                this.alpha = 1.0F;
            }
            return this.alpha;
        }

        if (phase == Phase.FADING_IN || phase == Phase.ACTIVE) {
            this.phase = Phase.RETIRING;
            this.phaseStartNanos = nowNanos;
            this.fadeNanos = spanNanos;
            this.retireStartAlpha = alpha;
            return this.alpha;
        }
        if (phase == Phase.RETIRING) {
            float progress = progress(nowNanos, phaseStartNanos, fadeNanos);
            if (progress >= 1.0F) {
                this.phase = Phase.IDLE;
                this.alpha = 0.0F;
            } else {
                this.alpha = retireStartAlpha * (1.0F - progress);
            }
            return this.alpha;
        }
        // IDLE 且未激活：恒返回 0（防御语义；清空依据是相位而非 alpha）
        this.alpha = 0.0F;
        return 0.0F;
    }

    /** @return 当前相位 */
    public Phase getPhase() {
        return phase;
    }

    /** @return 是否处于 retiring（保留最后一份 mesh 淡出中） */
    public boolean isRetiring() {
        return phase == Phase.RETIRING;
    }

    /** @return 是否已回到 IDLE（retiring 结束 / 未激活且无过渡） */
    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    /** @return 最近一次 {@link #advance} 的不透明度乘子 */
    public float getAlpha() {
        return alpha;
    }

    /** @return 最近一次 {@link #advance} 绑定的代；未绑定为 {@link Integer#MIN_VALUE} */
    public int getGeneration() {
        return generation;
    }

    /** 清理状态（预览结束 / 世界切换 / 后端热切换）。 */
    public void reset() {
        phase = Phase.IDLE;
        generation = Integer.MIN_VALUE;
        phaseStartNanos = 0L;
        fadeNanos = 0L;
        retireStartAlpha = 1.0F;
        alpha = 0.0F;
    }

    private static float progress(long nowNanos, long startNanos, long spanNanos) {
        long elapsed = nowNanos - startNanos;
        if (elapsed <= 0L || spanNanos <= 0L) {
            return 0.0F;
        }
        if (elapsed >= spanNanos) {
            return 1.0F;
        }
        return (float) ((double) elapsed / (double) spanNanos);
    }
}
