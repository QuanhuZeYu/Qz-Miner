package club.heiqi.qz_miner.chain.client.render;

/**
 * 同代刷新（topology 未变）的上传决策纯函数。
 *
 * <p>着色器后端颜色完全由 GPU uniform 计算（{@code usesCpuColors() == false}），同代刷新必须
 * 零上传，且不得因颜色流不可用而退化为整份拓扑重传；legacy 后端仍走 CPU 颜色流，只有颜色流
 * 上传被拒时才按历史行为重传拓扑。函数不触碰 GL，供 renderer 分派与 headless 断言共用。</p>
 */
public final class ChainPreviewRefreshDecision {

    /** 本次同代刷新需要执行的上传动作。 */
    public enum Upload {
        /** 整份拓扑重传（拓扑变化，或 legacy 颜色流不可用）。 */
        TOPOLOGY,
        /** 仅 CPU 颜色流上传。 */
        COLORS,
        /** 零上传（shader 同代刷新）。 */
        NONE
    }

    private ChainPreviewRefreshDecision() {
    }

    /**
     * 同代刷新开始时的动作。
     *
     * @param topologyChanged generation / stateRevision 变化（或网格为空）时为 true
     * @param usesCpuColors   后端是否消费 CPU 颜色流
     * @return {@code TOPOLOGY} / {@code COLORS}（需尝试颜色上传）/ {@code NONE}
     */
    public static Upload begin(boolean topologyChanged, boolean usesCpuColors) {
        if (topologyChanged) {
            return Upload.TOPOLOGY;
        }
        return usesCpuColors ? Upload.COLORS : Upload.NONE;
    }

    /**
     * 颜色流上传被拒（legacy 的 topology 守卫未通过）后的动作：保持历史行为退化为拓扑重传。
     *
     * @param attempted {@link #begin} 的返回值
     * @return 退化后的动作；非 {@code COLORS} 原样返回
     */
    public static Upload fallback(Upload attempted) {
        return attempted == Upload.COLORS ? Upload.TOPOLOGY : attempted;
    }
}
