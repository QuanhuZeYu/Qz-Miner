package club.heiqi.qz_miner.chain.client.render;

/**
 * 同拓扑刷新时按后端消费面决定上传动作：shader 通过 uniform 刷新，legacy 上传 CPU 颜色。
 * legacy 颜色上传被拒时重传拓扑，renderer 与离线验证共用本决策。
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
