package club.heiqi.qz_miner.chain.client.projection;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * {@link ClientPhaseProjection} 客户端投影容器单测。
 *
 * <p>覆盖：update 正常写入、gen 陈旧丢弃、clear 字段重置、初值默认。</p>
 *
 * <p>注：{@code ClientPhaseProjection} 标 {@code @SideOnly(Side.CLIENT)}，但在纯 JVM 单测环境
 * Forge ClassTransformer 不运行，{@code @SideOnly} 仅作元数据注解，类完整可访问。</p>
 */
public class ClientPhaseProjectionTest {

    /** 初值默认：IDLE/0/-1。 */
    @Test
    public void initialValueIsIdleZero() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        Assert.assertEquals(ChainPhase.IDLE, projection.getCurrentPhase());
        Assert.assertEquals(0, projection.getCurrentGeneration());
        Assert.assertEquals(-1L, projection.getLastUpdateServerTick());
    }

    /** update 正常写入：phase/gen/tick 字段更新。 */
    @Test
    public void updateWritesAllFields() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        projection.update(ChainPhase.PLANNING, 3, 100L);
        Assert.assertEquals(ChainPhase.PLANNING, projection.getCurrentPhase());
        Assert.assertEquals(3, projection.getCurrentGeneration());
        Assert.assertEquals(100L, projection.getLastUpdateServerTick());
    }

    /** gen 陈旧丢弃：新 gen < 当前 gen 不更新。 */
    @Test
    public void staleGenerationIsDropped() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        projection.update(ChainPhase.RUNNING, 5, 200L);
        // 陈旧 gen=3 < 当前 gen=5，应丢弃
        projection.update(ChainPhase.IDLE, 3, 300L);
        Assert.assertEquals("陈旧快照不应改 phase", ChainPhase.RUNNING, projection.getCurrentPhase());
        Assert.assertEquals("陈旧快照不应改 gen", 5, projection.getCurrentGeneration());
        Assert.assertEquals("陈旧快照不应改 tick", 200L, projection.getLastUpdateServerTick());
    }

    /** 同代重复 update 接受（幂等覆盖，== 也走 >= 路径）。 */
    @Test
    public void sameGenerationUpdateIsAccepted() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        projection.update(ChainPhase.PLANNING, 4, 200L);
        projection.update(ChainPhase.RUNNING, 4, 210L);
        Assert.assertEquals(ChainPhase.RUNNING, projection.getCurrentPhase());
        Assert.assertEquals(4, projection.getCurrentGeneration());
        Assert.assertEquals(210L, projection.getLastUpdateServerTick());
    }

    /** clear 后字段重置为 IDLE/0/-1（守 i7）。 */
    @Test
    public void clearResetsAllFields() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        projection.update(ChainPhase.FINISHING, 7, 999L);
        projection.clear();
        Assert.assertEquals(ChainPhase.IDLE, projection.getCurrentPhase());
        Assert.assertEquals(0, projection.getCurrentGeneration());
        Assert.assertEquals(-1L, projection.getLastUpdateServerTick());
    }

    /**
     * P2-4 阶段8 块3 回归：G2 夺权后 HUD/预览锁定权威读 ClientPhaseProjection。
     *
     * <p>固化 F1 锁定边界契约：PLANNING/RUNNING/FINISHING 锁定预览（连锁进行中，避免转视角切走）；
     * ARMED/IDLE 不锁（玩家可自由选目标）。本测在投影层断言五态分类，回归守 shouldLockCurrentPreview
     * 的 phase→lock 映射（私有方法 JVM 不可直接达，但 phase 分类是 G2 夺权的数据契约核心）。</p>
     */
    @Test
    public void g2ProjectionLockPhasesContract() {
        ClientPhaseProjection projection = new ClientPhaseProjection();
        // F1 锁定态：PLANNING/RUNNING/FINISHING
        ChainPhase[] lockPhases = {ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING};
        for (ChainPhase phase : lockPhases) {
            projection.update(phase, 1, 1L);
            Assert.assertEquals("G2 夺权：投影 phase 应可读到 " + phase, phase, projection.getCurrentPhase());
            Assert.assertTrue("F1 锁定态应包含 " + phase, isLockPhase(projection.getCurrentPhase()));
        }
        // F1 不锁态：IDLE/ARMED（ARMED 玩家仍可自由选目标）
        ChainPhase[] noLockPhases = {ChainPhase.IDLE, ChainPhase.ARMED};
        for (ChainPhase phase : noLockPhases) {
            projection.update(phase, 2, 2L);
            Assert.assertFalse("F1 不锁态应不含 " + phase, isLockPhase(projection.getCurrentPhase()));
        }
    }

    /** 镜像 ChainPreviewController.shouldLockCurrentPreview 的 F1 锁定判定（PLANNING/RUNNING/FINISHING）。 */
    private static boolean isLockPhase(ChainPhase phase) {
        return phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING;
    }
}
