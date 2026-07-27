package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * 本地成功破坏 origin 后、服务端阶段投影可见前的预览租约。
 *
 * <p>该对象只记录客户端已观察到的 phase/generation，不写状态机，也不伪造服务端阶段。</p>
 */
final class PreviewOriginLease {

    private boolean active;
    private int triggerGeneration;
    private boolean observedActivePhase;
    private int activeGeneration;

    /** 以本地破坏瞬间可见的服务端投影作为租约基线。 */
    void acquire(ChainPhase phase, int generation) {
        active = true;
        triggerGeneration = generation;
        observedActivePhase = isActivePhase(phase);
        activeGeneration = observedActivePhase ? generation : Integer.MIN_VALUE;
    }

    /**
     * 更新已观察投影并判断是否继续锁定 origin。
     *
     * <p>触发后同代 IDLE/ARMED 仍可能只是 phase 迟到，必须保守锁定。只要观察到 active phase，
     * 就等待该 generation 的后续终态；若 active 快照丢失，则 generation 推进后的终态也可释放。</p>
     */
    boolean shouldLock(ChainPhase phase, int generation) {
        if (!active) {
            return false;
        }
        if (isActivePhase(phase)) {
            observedActivePhase = true;
            activeGeneration = Math.max(activeGeneration, generation);
            return true;
        }

        boolean terminalObserved = observedActivePhase
                ? generation >= activeGeneration
                : generation > triggerGeneration;
        if (terminalObserved) {
            reset();
            return false;
        }
        return true;
    }

    /** 清除当前本地 origin 租约。 */
    void reset() {
        active = false;
        triggerGeneration = 0;
        observedActivePhase = false;
        activeGeneration = Integer.MIN_VALUE;
    }

    /** @return 当前是否持有本地 origin 租约 */
    boolean isActive() {
        return active;
    }

    private static boolean isActivePhase(ChainPhase phase) {
        return phase == ChainPhase.PLANNING
                || phase == ChainPhase.RUNNING
                || phase == ChainPhase.FINISHING;
    }
}
