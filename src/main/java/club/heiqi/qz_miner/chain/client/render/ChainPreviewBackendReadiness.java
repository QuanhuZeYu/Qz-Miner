package club.heiqi.qz_miner.chain.client.render;

/**
 * 纯决策：后端 {@code ensureReady()==false} 时的动作（T48c-B 简化后的兜底入口）。
 *
 * <p>复用既有「一次性永久回退」语义，不引入新的 GL 对象 / 回读探针：能力不足或不可验证时
 * 一律收敛到 legacy，且由 renderer 的 shaderAttemptFailed 保证不再重试。</p>
 *
 * <ul>
 *   <li>未就绪的是 legacy → {@link Action#KEEP_ACTIVE}：保持既有行为（预览整体不可用，
 *       不切换后端、不重复创建）；</li>
 *   <li>未就绪的是 shader 且 legacy 能力可用 → {@link Action#SWITCH_TO_LEGACY}：
 *       一次性永久回退（调用方负责 dispose + reset + 一次性 WARN）；</li>
 *   <li>未就绪的是 shader 且 legacy 不可用 → {@link Action#NO_USABLE_PATH}：
 *       显式降级，不创建必然失败的后端。</li>
 * </ul>
 */
public final class ChainPreviewBackendReadiness {

    /** 未就绪后端应执行的动作。 */
    public enum Action {
        /** 保持当前后端（legacy 未就绪：既有行为）。 */
        KEEP_ACTIVE,
        /** 一次性永久回退 legacy。 */
        SWITCH_TO_LEGACY,
        /** 无可用路径：显式降级不绘制。 */
        NO_USABLE_PATH
    }

    private ChainPreviewBackendReadiness() {
    }

    /**
     * 纯函数：由「未就绪后端 id + legacy 路径可用性」决定动作；null / 未知 id 一律按 shader 处理。
     *
     * @param activeId    未就绪后端的 id（shader / legacy / null / 未知）
     * @param legacyUsable legacy 路径能力是否可用（见 {@link ChainPreviewOverlayPath#legacyUnavailableReason}）
     * @return 动作，永不为 null
     */
    public static Action onNotReady(String activeId, boolean legacyUsable) {
        if (ChainPreviewBackendSelector.LEGACY.equals(activeId)) {
            return Action.KEEP_ACTIVE;
        }
        return legacyUsable ? Action.SWITCH_TO_LEGACY : Action.NO_USABLE_PATH;
    }

    /** @return 该动作是否构成一次性回退（供调用方打一次性 WARN） */
    public static boolean isFallback(Action action) {
        return action == Action.SWITCH_TO_LEGACY;
    }
}
