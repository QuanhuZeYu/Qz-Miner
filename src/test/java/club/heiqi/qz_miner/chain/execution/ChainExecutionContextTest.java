package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * {@link ChainExecutionContext} 与 {@link ChainExecutionContextRegistry} 纯逻辑单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link ChainExecutionContext#isCompleted()} 判定（空队列 true、非空 false、poll 后变空触发完成）。</li>
 *   <li>{@link ChainExecutionContextRegistry#put} / {@code get} / {@code remove}（含 gen 校验）。</li>
 *   <li>陈旧 gen 领取被拒（卡点6 之外的 registry 层防护）。</li>
 * </ul>
 *
 * <p>纯 JVM 测试，不依赖 worldObj/player/session，不实例化 GuiScreen。</p>
 */
public class ChainExecutionContextTest {

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000AA");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000BB");

    // ============================ ChainExecutionContext.isCompleted ============================

    /** 空队列构造时 isCompleted() 返回 true（卡点5 空规划边界判定基础）。 */
    @Test
    public void emptyTargetsIsCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue);
        Assert.assertTrue("空队列初始即完成", context.isCompleted());
    }

    /** 非空队列构造时 isCompleted() 返回 false。 */
    @Test
    public void nonEmptyTargetsIsNotCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue);
        Assert.assertFalse("非空队列未完成", context.isCompleted());
    }

    /** poll 全部目标后 isCompleted() 变 true（消费完成判定）。 */
    @Test
    public void pollAllTargetsBecomesCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        queue.add(new ChainTarget(4, 5, 6));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue);
        Assert.assertFalse(context.isCompleted());

        // 模拟 dry-run 执行订阅者消费：poll + 计数（不破坏）
        int executed = 0;
        while (executed < 16) {
            ChainTarget t = context.getTargets().poll();
            if (t == null) {
                break;
            }
            executed++;
        }
        Assert.assertEquals("应消费 2 个目标", 2, executed);
        Assert.assertTrue("消费完后应判定完成", context.isCompleted());
    }

    /** getter 字段一致性（playerUUID/generation 引用）。 */
    @Test
    public void gettersCarryConstructorArgs() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 7, queue);
        Assert.assertEquals(PLAYER_A, context.getPlayerUUID());
        Assert.assertEquals("generation 必须原样回填（事件流锚点）", 7, context.getGeneration());
        Assert.assertSame(queue, context.getTargets());
    }

    /** nextExecutorAllowedMillis 节流字段默认 0，可读写（dry-run 不强制读，留阶段8 复用）。 */
    @Test
    public void throttleFieldDefaultZeroAndMutable() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>());
        Assert.assertEquals(0L, context.getNextExecutorAllowedMillis());
        context.setNextExecutorAllowedMillis(12345L);
        Assert.assertEquals(12345L, context.getNextExecutorAllowedMillis());
    }

    // ============================ ChainExecutionContextRegistry gen 校验 ============================

    /** put 后 get(uuid, gen) 领取正确 context。 */
    @Test
    public void registryPutAndGetMatchesGen() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 5, queue);
        registry.put(context);

        ChainExecutionContext got = registry.get(PLAYER_A, 5);
        Assert.assertSame("匹配 gen 应返回同一 context", context, got);
    }

    /** get 用陈旧 gen 领取返回 null（卡点6 之外的 registry 层防护）。 */
    @Test
    public void registryGetStaleGenReturnsNull() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 5,
                new ConcurrentLinkedQueue<ChainTarget>());
        registry.put(context);

        Assert.assertNull("陈旧 gen 领取应被拒（gen=4 < current=5）", registry.get(PLAYER_A, 4));
        Assert.assertNull("未来 gen 领取应被拒（gen=6 > current=5）", registry.get(PLAYER_A, 6));
    }

    /** get 不存在的玩家返回 null。 */
    @Test
    public void registryGetMissingPlayerReturnsNull() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        Assert.assertNull(registry.get(PLAYER_A, 0));
    }

    /** put 时新 gen > 旧 gen 才覆盖（防陈旧 worker 迟到 put 覆盖新 context）。 */
    @Test
    public void registryPutOverwritesOnlyWhenGenGreater() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ConcurrentLinkedQueue<ChainTarget> q1 = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext oldContext = new ChainExecutionContext(PLAYER_A, 3, q1);
        registry.put(oldContext);

        ConcurrentLinkedQueue<ChainTarget> q2 = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext newContext = new ChainExecutionContext(PLAYER_A, 5, q2);
        registry.put(newContext);

        // 新 gen=5 应覆盖 gen=3
        Assert.assertSame(newContext, registry.get(PLAYER_A, 5));
        Assert.assertNull("旧 gen=3 应已被覆盖", registry.get(PLAYER_A, 3));

        // 再 put 一次 gen=4（小于当前 5），不应覆盖
        ConcurrentLinkedQueue<ChainTarget> q3 = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext staleContext = new ChainExecutionContext(PLAYER_A, 4, q3);
        registry.put(staleContext);
        Assert.assertSame("陈旧 worker 迟到 put 不应覆盖", newContext, registry.get(PLAYER_A, 5));
    }

    /** remove 后 get 返回 null。 */
    @Test
    public void registryRemoveClearsContext() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>());
        registry.put(context);
        Assert.assertNotNull(registry.get(PLAYER_A, 1));

        registry.remove(PLAYER_A);
        Assert.assertNull(registry.get(PLAYER_A, 1));
    }

    /** snapshot 返回当前所有活跃 context（弱一致视图）。 */
    @Test
    public void registrySnapshotContainsAllActive() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext ctxA = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>());
        ChainExecutionContext ctxB = new ChainExecutionContext(PLAYER_B, 1,
                new ConcurrentLinkedQueue<ChainTarget>());
        registry.put(ctxA);
        registry.put(ctxB);

        Assert.assertEquals(2, registry.snapshot().size());
        Assert.assertTrue(registry.snapshot().contains(ctxA));
        Assert.assertTrue(registry.snapshot().contains(ctxB));
    }
}
