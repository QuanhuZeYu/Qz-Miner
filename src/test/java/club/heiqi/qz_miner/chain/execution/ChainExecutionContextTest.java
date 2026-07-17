package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;

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

    // ============================ ChainExecutionContext.isCompleted（流式语义） ============================

    /**
     * 流式语义：planningComplete=false && queue 空 → isCompleted() 返回 false。
     *
     * <p>worker 仍在搜、queue 瞬时为空时，主线程消费订阅者<b>不应</b>误判 ExecutionFinished 提前 publish
     * （否则卡死 RUNNING）。需要等 worker markPlanningComplete 后才可判定完成。</p>
     */
    @Test
    public void emptyTargetsButNotPlanningCompleteIsIncomplete() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue, null);
        Assert.assertFalse("planningComplete=false 时空 queue 不应判定完成", context.isCompleted());
    }

    /** 流式语义：planningComplete=true && queue 空 → isCompleted() 返回 true（空规划边界 / 全消费完成）。 */
    @Test
    public void emptyTargetsAndPlanningCompleteIsCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue, null);
        context.markPlanningComplete();
        Assert.assertTrue("planningComplete=true 且空 queue 应判定完成", context.isCompleted());
    }

    /** 流式语义：planningComplete=true && queue 非空 → isCompleted() 返回 false（仍有目标待消费）。 */
    @Test
    public void nonEmptyTargetsEvenAfterPlanningCompleteIsNotCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue, null);
        context.markPlanningComplete();
        Assert.assertFalse("planningComplete=true 但 queue 非空仍不应判定完成", context.isCompleted());
    }

    /** 流式语义：poll 全部目标后 + markPlanningComplete 才 isCompleted() 变 true（消费完成判定）。 */
    @Test
    public void pollAllTargetsAndMarkPlanningCompleteBecomesCompleted() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        queue.add(new ChainTarget(4, 5, 6));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1, queue, null);
        Assert.assertFalse(context.isCompleted());

        // 模拟真实破坏执行订阅者消费：poll + 计数（单测只计数不破坏，生产路径由 ChainActionExecutor 破坏）
        int executed = 0;
        while (executed < 16) {
            ChainTarget t = context.getTargets().poll();
            if (t == null) {
                break;
            }
            executed++;
        }
        Assert.assertEquals("应消费 2 个目标", 2, executed);
        // 仅消费完但未 markPlanningComplete：仍不应判定完成（流式铁律）
        Assert.assertFalse("消费完但未 markPlanningComplete 不应判定完成", context.isCompleted());
        context.markPlanningComplete();
        Assert.assertTrue("消费完且 markPlanningComplete 后应判定完成", context.isCompleted());
    }

    /** markPlanningComplete 后 isPlanningComplete() 返回 true（worker 完成路径铁律锚点）。 */
    @Test
    public void markPlanningCompleteFlipsFlag() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        Assert.assertFalse("构造时 planningComplete=false", context.isPlanningComplete());
        context.markPlanningComplete();
        Assert.assertTrue("markPlanningComplete 后 isPlanningComplete()=true", context.isPlanningComplete());
    }

    /** getter 字段一致性（playerUUID/generation 引用）。 */
    @Test
    public void gettersCarryConstructorArgs() {
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 7, queue, null);
        Assert.assertEquals(PLAYER_A, context.getPlayerUUID());
        Assert.assertEquals("generation 必须原样回填（事件流锚点）", 7, context.getGeneration());
        Assert.assertSame(queue, context.getTargets());
    }

    /** nextExecutorAllowedMillis 节流字段默认 0，可读写（阶段8 块2 起真实破坏桥控速复用）。 */
    @Test
    public void throttleFieldDefaultZeroAndMutable() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        Assert.assertEquals(0L, context.getNextExecutorAllowedMillis());
        context.setNextExecutorAllowedMillis(12345L);
        Assert.assertEquals(12345L, context.getNextExecutorAllowedMillis());
    }

    /** isExecutorReady 控速闸门：未到允许戳返回 false，已到或越过返回 true。 */
    @Test
    public void isExecutorReadyThrottleGate() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        // 默认 0L，任意正数时间戳都应就绪
        Assert.assertTrue("默认戳 0，nowMillis=100 应已就绪", context.isExecutorReady(100L));
        context.setNextExecutorAllowedMillis(500L);
        Assert.assertFalse("nowMillis=499 < 500，应未就绪", context.isExecutorReady(499L));
        Assert.assertTrue("nowMillis=500 = 500，应就绪", context.isExecutorReady(500L));
        Assert.assertTrue("nowMillis=501 > 500，应就绪", context.isExecutorReady(501L));
    }

    /**
     * 阶段8 块2 E1：session 字段经构造注入后 getSession() 返回同一引用。
     *
     * <p>真实破坏桥通过 getSession() 拿 session 解析 mode/subMode → ChainModeRegistry → actionExecutor。
     * 单测构造可传 null（不依赖运行时装配），生产路径由 worker 传入 shadowSession。</p>
     */
    @Test
    public void sessionCarriedByConstructor() {
        ChainSession session = new ChainSession(PLAYER_A, null, null, new ChainTarget(1, 2, 3));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), session);
        Assert.assertSame("session 必须原样携带（真实破坏桥参数载体）", session, context.getSession());
    }

    /** session=null 边界（单测路径允许，真实破坏桥需自行判 null 走 publish ExecutionFinished）。 */
    @Test
    public void nullSessionAllowed() {
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        Assert.assertNull(context.getSession());
    }

    // ============================ ChainExecutionContextRegistry gen 校验 ============================

    /** put 后 get(uuid, gen) 领取正确 context。 */
    @Test
    public void registryPutAndGetMatchesGen() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 5, queue, null);
        registry.put(context);

        ChainExecutionContext got = registry.get(PLAYER_A, 5);
        Assert.assertSame("匹配 gen 应返回同一 context", context, got);
    }

    /** get 用陈旧 gen 领取返回 null（卡点6 之外的 registry 层防护）。 */
    @Test
    public void registryGetStaleGenReturnsNull() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 5,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
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
        ChainExecutionContext oldContext = new ChainExecutionContext(PLAYER_A, 3, q1, null);
        registry.put(oldContext);

        ConcurrentLinkedQueue<ChainTarget> q2 = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext newContext = new ChainExecutionContext(PLAYER_A, 5, q2, null);
        registry.put(newContext);

        // 新 gen=5 应覆盖 gen=3
        Assert.assertSame(newContext, registry.get(PLAYER_A, 5));
        Assert.assertNull("旧 gen=3 应已被覆盖", registry.get(PLAYER_A, 3));

        // 再 put 一次 gen=4（小于当前 5），不应覆盖
        ConcurrentLinkedQueue<ChainTarget> q3 = new ConcurrentLinkedQueue<ChainTarget>();
        ChainExecutionContext staleContext = new ChainExecutionContext(PLAYER_A, 4, q3, null);
        registry.put(staleContext);
        Assert.assertSame("陈旧 worker 迟到 put 不应覆盖", newContext, registry.get(PLAYER_A, 5));
    }

    /** remove 后 get 返回 null。 */
    @Test
    public void registryRemoveClearsContext() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext context = new ChainExecutionContext(PLAYER_A, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
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
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        ChainExecutionContext ctxB = new ChainExecutionContext(PLAYER_B, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        registry.put(ctxA);
        registry.put(ctxB);

        Assert.assertEquals(2, registry.snapshot().size());
        Assert.assertTrue(registry.snapshot().contains(ctxA));
        Assert.assertTrue(registry.snapshot().contains(ctxB));
    }

    /** 同 generation 的 R1/R2 必须按 round 隔离，旧轮不得领取或删除 R2 context。 */
    @Test
    public void registrySeparatesSameGenerationDifferentRounds() {
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext r1 = new ChainExecutionContext(PLAYER_A, 401L, 7,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        ChainExecutionContext r2 = new ChainExecutionContext(PLAYER_A, 402L, 7,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        registry.put(r1);
        registry.put(r2);

        Assert.assertNull("R1 不得领取 R2 context", registry.get(PLAYER_A, 7, 401L));
        Assert.assertSame("R2 必须保留当前 context", r2, registry.get(PLAYER_A, 7, 402L));
        Assert.assertFalse("R1 cleanup 不得删除 R2 context", registry.remove(PLAYER_A, 7, 401L));
        Assert.assertSame(r2, registry.get(PLAYER_A, 7, 402L));
        Assert.assertTrue(registry.remove(PLAYER_A, 7, 402L));
    }
}
