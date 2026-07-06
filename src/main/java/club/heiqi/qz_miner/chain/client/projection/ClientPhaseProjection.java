package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 阶段6 A2：客户端连锁阶段投影容器（单玩家，P1-2=A 决议）。
 *
 * <p>客户端只有本地玩家，单实例容器。{@code currentPhase}/{@code currentGeneration}/
 * {@code lastUpdateServerTick} 三个 volatile 字段供 HUD/诊断读取"服务端状态机最新态"。</p>
 *
 * <p><b>阶段8 块3 起投影夺权</b>：HUD/预览锁定权威已从旧 {@code serverExecutionStatus}
 * 切到本投影（G2 不夺权铁律解除）。阶段6-7 影子期的"只可见不夺权"已结束。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I4</b>：本容器 {@code update} 由 {@code ClientPhaseProjectionSubscriber} 在
 *       ClientTickEvent.START drain（客户端主线程）调用，不在 Netty 线程直接改。</li>
 *   <li><b>I7</b>：玩家断线/切维度/世界卸载时由 {@code ClientConnectionListener} 调 {@link #clear()}。</li>
 *   <li><b>I10</b>：客户端没有状态机实例，投影容器与状态机物理隔离，只可见不可切态。</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class ClientPhaseProjection {

    /** 当前投影阶段，初值 IDLE。volatile 守 I4 跨线程可见性（实际写都在客户端主线程 drain）。 */
    private volatile ChainPhase currentPhase = ChainPhase.IDLE;
    /** 当前投影代际，初值 0。 */
    private volatile int currentGeneration = 0;
    /** 最后一次更新的服务端 tick（诊断字段）。 */
    private volatile long lastUpdateServerTick = -1L;

    /**
     * 更新投影。
     *
     * <p>代际陈旧判定：新 {@code generation < currentGeneration} 丢弃（迟到旧快照）；
     * {@code >=} 接受（== 是同代重复，幂等覆盖；> 是新代际，正常更新）。</p>
     *
     * @param phase       目标态
     * @param generation  转移后的新代际
     * @param serverTick  发布时服务端 tick
     */
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
     * 清理投影（守 I7：玩家断线/切维度/世界卸载时调用）。
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
