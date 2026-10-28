package club.heiqi.qz_miner.chain.client.render;

/**
 * 决定后端未就绪时的后续动作：legacy 保持当前实例，shader 按能力回退 legacy 或标记无可用路径。
 * renderer 负责释放旧实例、记录原因与锁存失败。
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
