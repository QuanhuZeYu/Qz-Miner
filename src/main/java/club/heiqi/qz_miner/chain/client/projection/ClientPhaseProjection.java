package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端维护的服务端阶段投影，供 HUD 与预览锁定条件消费。
 * 订阅者在客户端主线程 drain 时应用匹配或较新的代际，serverTick 留作诊断。
 * 连接与世界生命周期切换时统一清空投影。
 */
@SideOnly(Side.CLIENT)
public class ClientPhaseProjection {

    /** 当前投影阶段，初值 IDLE，由客户端主线程 drain 更新。 */
    private volatile ChainPhase currentPhase = ChainPhase.IDLE;
    /** 当前投影代际，初值 0。 */
    private volatile int currentGeneration = 0;
    /** 最后一次更新的服务端 tick（诊断字段）。 */
    private volatile long lastUpdateServerTick = -1L;

    /** 应用相同或较新的代际快照，保留本次快照的诊断 tick。 */
    public void update(ChainPhase phase, int generation, long serverTick) {
        if (generation < currentGeneration) {
            // 陈旧快照迟到，丢弃
            return;
        }
        this.currentPhase = phase;
        this.currentGeneration = generation;
        this.lastUpdateServerTick = serverTick;
    }

    /**
     * 连接或世界生命周期切换时重置为本机 IDLE 初态。
     */
    public void clear() {
        this.currentPhase = ChainPhase.IDLE;
        this.currentGeneration = 0;
        this.lastUpdateServerTick = -1L;
    }

    /** @return 当前投影阶段 */
    public ChainPhase getCurrentPhase() {
        return currentPhase;
    }

    /** @return 当前投影代际 */
    public int getCurrentGeneration() {
        return currentGeneration;
    }

    /** @return 最后一次更新的服务端 tick（诊断字段） */
    public long getLastUpdateServerTick() {
        return lastUpdateServerTick;
    }
}
