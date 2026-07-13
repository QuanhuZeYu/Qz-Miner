package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link ClientConnectionListener} 真实调度路径门禁。
 *
 * <p>经 package-private {@code handleConnected}/{@code handleDisconnected}/
 * {@code handleWorldLoad}/{@code handleWorldUnload} 与生产 {@code @SubscribeEvent}
 * 同一委托入口，配合可注入主线程队列，确定性覆盖：</p>
 * <ul>
 *   <li>transition 判断 → dispatcher 排队次数</li>
 *   <li>token gate → takeover / init / cleanup 分支与执行顺序</li>
 *   <li>重复 connect/load、迟到 disconnect/unload、A cleanup 在 B 后 no-op</li>
 * </ul>
 *
 * <p>不实例化 GuiScreen / Minecraft / FML 事件对象；handler/world 用普通 Object 模拟
 * identity（{@code ==}）。关键断言在删除 {@code if (!transitioned) return} 或接错 token
 * 时应失败。</p>
 */
public class ClientConnectionListenerTest {

    private static final long LATCH_TIMEOUT_MS = 5_000L;

    private Object handlerA;
    private Object handlerB;
    private Object worldA;
    private Object worldB;
    private QueueDispatcher dispatcher;
    private ClientConnectionListener listener;
    private final List<String> actions = new ArrayList<String>();
    private final AtomicInteger inits = new AtomicInteger(0);
    private final AtomicInteger resourceMarker = new AtomicInteger(0);
    private final AtomicInteger projection = new AtomicInteger(-1);

    @Before
    public void setUp() {
        ClientConnectionLifecycle.resetForTests();
        handlerA = new Object();
        handlerB = new Object();
        worldA = new Object();
        worldB = new Object();
        dispatcher = new QueueDispatcher();
        listener = new ClientConnectionListener(dispatcher);
        actions.clear();
        inits.set(0);
        resourceMarker.set(0);
        projection.set(-1);
        installHooks();
    }

    @After
    public void tearDown() {
        ClientConnectionLifecycle.resetForTests();
    }

    private void installHooks() {
        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                actions.add("cleanup:" + reason);
                resourceMarker.set(0);
            }
        };
        listener.initHookForTests = new Runnable() {
            @Override
            public void run() {
                actions.add("init");
                inits.incrementAndGet();
                projection.set(0);
            }
        };
    }

    /**
     * 重复 connect 只排一次 init；有效 S2C 后投影不被回退、不重复 C2S/init。
     */
    @Test
    public void repeatConnectQueuesInitOnceAndDoesNotResetAfterS2c() {
        listener.handleConnected(handlerA);
        Assert.assertEquals(1, dispatcher.size());
        dispatcher.runAll();
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(0, projection.get());
        Assert.assertTrue(actions.contains("cleanup:connection-takeover"));
        Assert.assertTrue(actions.contains("init"));

        // 模拟有效 S2C 写投影
        projection.set(42);
        resourceMarker.set(1);
        int actionsBefore = actions.size();

        listener.handleConnected(handlerA);
        Assert.assertEquals(
                "repeat connect must not enqueue another init",
                0,
                dispatcher.size());
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(42, projection.get());
        Assert.assertEquals(actionsBefore, actions.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertSame(handlerA, ClientConnectionLifecycle.capture().connectionIdentity());
    }

    /**
     * connect B 替换 A：B init 实际排队；按顺序 takeover cleanup → reset → C2S(init)。
     */
    @Test
    public void connectBReplacesAQueuesTakeoverThenInitInOrder() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        resourceMarker.set(1);
        projection.set(99);
        actions.clear();
        inits.set(0);

        listener.handleConnected(handlerB);
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertSame(handlerB, ClientConnectionLifecycle.capture().connectionIdentity());

        dispatcher.runAll();
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertEquals(0, projection.get());
        Assert.assertEquals(2, actions.size());
        Assert.assertEquals("cleanup:connection-takeover", actions.get(0));
        Assert.assertEquals("init", actions.get(1));
    }

    /**
     * A 迟到 disconnect 在 B 已 connect 后不排 cleanup。
     */
    @Test
    public void lateDisconnectOfAAfterBDoesNotEnqueueCleanup() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        listener.handleConnected(handlerB);
        Assert.assertEquals(1, dispatcher.size());
        dispatcher.runAll();
        int sizeAfterBInit = dispatcher.size();
        int actionsBefore = actions.size();

        listener.handleDisconnected(handlerA);
        Assert.assertEquals(sizeAfterBInit, dispatcher.size());
        Assert.assertEquals(actionsBefore, actions.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertSame(handlerB, ClientConnectionLifecycle.capture().connectionIdentity());
    }

    /**
     * A disconnect cleanup 已排后 B connect，再执行 A cleanup no-op 且 B init 接管。
     */
    @Test
    public void disconnectAQueuedThenBConnectMakesACleanupNoOpAndBInitTakesOver() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        resourceMarker.set(1);
        projection.set(55);
        actions.clear();
        inits.set(0);

        // A disconnect 排队 cleanup（尚未 drain）
        listener.handleDisconnected(handlerA);
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());

        // B connect 排队 init（A cleanup 仍在队列前方）
        listener.handleConnected(handlerB);
        Assert.assertEquals(2, dispatcher.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertSame(handlerB, ClientConnectionLifecycle.capture().connectionIdentity());

        // 先 drain A cleanup：token 已非 current inactive → no-op
        dispatcher.runNext();
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertEquals(0, actions.size());
        Assert.assertEquals(1, resourceMarker.get());
        Assert.assertEquals(55, projection.get());

        // 再 drain B init：takeover cleanup + init
        dispatcher.runNext();
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertEquals(0, projection.get());
        Assert.assertEquals(2, actions.size());
        Assert.assertEquals("cleanup:connection-takeover", actions.get(0));
        Assert.assertEquals("init", actions.get(1));
        Assert.assertSame(handlerB, ClientConnectionLifecycle.capture().connectionIdentity());
    }

    /**
     * world B load 替换 A 仅排一次 world takeover；重复 load 不排。
     */
    @Test
    public void worldBReplaceQueuesTakeoverOnceAndRepeatLoadDoesNot() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();

        // 首次 bind：transitioned 但 replaced=false → 不排
        listener.handleWorldLoad(worldA, true);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isWorldActive());
        Assert.assertSame(worldA, ClientConnectionLifecycle.capture().worldIdentity());

        resourceMarker.set(1);
        actions.clear();

        // B 替换 A：排一次 world-takeover
        listener.handleWorldLoad(worldB, true);
        Assert.assertEquals(1, dispatcher.size());
        dispatcher.runAll();
        Assert.assertEquals(1, actions.size());
        Assert.assertEquals("cleanup:world-takeover", actions.get(0));
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertSame(worldB, ClientConnectionLifecycle.capture().worldIdentity());

        int actionsBefore = actions.size();
        listener.handleWorldLoad(worldB, true);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertEquals(actionsBefore, actions.size());
    }

    /**
     * A 迟到 unload 不排；已排旧 cleanup 在 B 后 no-op。
     */
    @Test
    public void lateWorldUnloadOfADoesNotEnqueueAndQueuedACleanupNoOpAfterB() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        listener.handleWorldLoad(worldA, true);
        Assert.assertEquals(0, dispatcher.size());

        // A unload 排队 cleanup
        listener.handleWorldUnload(worldA, true);
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isWorldActive());

        // 直接 Load B（空槽首次 bind，不排 takeover）
        listener.handleWorldLoad(worldB, true);
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isWorldActive());
        Assert.assertSame(worldB, ClientConnectionLifecycle.capture().worldIdentity());

        resourceMarker.set(9);
        actions.clear();
        // 旧 A unload cleanup drain → no-op
        dispatcher.runNext();
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertEquals(0, actions.size());
        Assert.assertEquals(9, resourceMarker.get());
        Assert.assertSame(worldB, ClientConnectionLifecycle.capture().worldIdentity());

        // 迟到 unload A：不转移、不排队
        listener.handleWorldUnload(worldA, true);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isWorldActive());
    }

    /**
     * 另一条路径：A active → Load B 直接替换（replaced）→ takeover 排队；迟到 Unload A 不排。
     */
    @Test
    public void worldBDirectReplaceTakeoverThenLateUnloadAIsNoOp() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        listener.handleWorldLoad(worldA, true);
        resourceMarker.set(1);
        actions.clear();

        listener.handleWorldLoad(worldB, true);
        Assert.assertEquals(1, dispatcher.size());
        dispatcher.runAll();
        Assert.assertEquals(1, actions.size());
        Assert.assertEquals("cleanup:world-takeover", actions.get(0));

        int size = dispatcher.size();
        listener.handleWorldUnload(worldA, true);
        Assert.assertEquals(size, dispatcher.size());
        Assert.assertSame(worldB, ClientConnectionLifecycle.capture().worldIdentity());
    }

    /**
     * 服务端 world（remote=false）不绑定、不排队。
     */
    @Test
    public void nonRemoteWorldLoadAndUnloadAreIgnored() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        listener.handleWorldLoad(worldA, false);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isWorldActive());

        // 先正常绑定再尝试非 remote unload
        listener.handleWorldLoad(worldA, true);
        listener.handleWorldUnload(worldA, false);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isWorldActive());
    }

    /**
     * 正常 disconnect：transitioned 排队 cleanup，drain 后资源收敛、连接 inactive。
     */
    @Test
    public void disconnectQueuesCleanupAndDrainsToInactive() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        resourceMarker.set(1);
        actions.clear();

        listener.handleDisconnected(handlerA);
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());

        dispatcher.runAll();
        Assert.assertEquals(1, actions.size());
        Assert.assertEquals("cleanup:client-disconnect", actions.get(0));
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());

        // 重复 disconnect 不排
        listener.handleDisconnected(handlerA);
        Assert.assertEquals(0, dispatcher.size());
    }

    /**
     * 正常 world unload：排队 cleanup 后 drain 收敛。
     */
    @Test
    public void worldUnloadQueuesCleanupAndDrains() {
        listener.handleConnected(handlerA);
        dispatcher.runAll();
        listener.handleWorldLoad(worldA, true);
        resourceMarker.set(1);
        actions.clear();

        listener.handleWorldUnload(worldA, true);
        Assert.assertEquals(1, dispatcher.size());
        dispatcher.runAll();
        Assert.assertEquals(1, actions.size());
        Assert.assertEquals("cleanup:client-world-unload", actions.get(0));
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isWorldActive());
    }

    /** takeover 每个清理子项故障均被隔离，其余子项与后续 init 仍执行，drain 不抛。 */
    @Test public void takeoverCleanupIsolatesEveryStepAndStillInitializes() {
        assertCleanupFailuresAreIsolated(false);
        assertCleanupFailuresAreIsolated(true);
    }

    /** disconnect 真实入口中的对象组与资源清理故障不逃逸 dispatcher drain。 */
    @Test public void disconnectCleanupIsolatesEveryStepWithoutEscapingDrain() {
        assertCleanupFailuresAreIsolatedOnDisconnect(false);
        assertCleanupFailuresAreIsolatedOnDisconnect(true);
    }

    private void assertCleanupFailuresAreIsolated(boolean linkageError) {
        for (int failedStep = 0; failedStep < 6; failedStep++) {
            ClientConnectionLifecycle.resetForTests();
            QueueDispatcher queue = new QueueDispatcher();
            FaultInjectingCleanup cleanup = new FaultInjectingCleanup(
                    FaultInjectingCleanup.STEPS[failedStep], linkageError);
            ClientConnectionListener subject = new ClientConnectionListener(queue, cleanup);
            final AtomicInteger initCount = new AtomicInteger();
            subject.initHookForTests = new Runnable() { @Override public void run() { initCount.incrementAndGet(); }};

            subject.handleConnected(new Object());
            Assert.assertEquals(1, queue.size());
            queue.runAll();

            Assert.assertEquals("all cleanup steps must be attempted", FaultInjectingCleanup.expectedSteps(), cleanup.attempts);
            Assert.assertEquals("takeover init must continue after cleanup failure", 1, initCount.get());
            Assert.assertEquals("dispatcher drain must complete", 0, queue.size());
        }
    }

    private void assertCleanupFailuresAreIsolatedOnDisconnect(boolean linkageError) {
        for (int failedStep = 0; failedStep < 6; failedStep++) {
            ClientConnectionLifecycle.resetForTests();
            QueueDispatcher queue = new QueueDispatcher();
            FaultInjectingCleanup cleanup = new FaultInjectingCleanup(
                    FaultInjectingCleanup.STEPS[failedStep], linkageError);
            ClientConnectionListener subject = new ClientConnectionListener(queue, cleanup);
            final AtomicInteger initCount = new AtomicInteger();
            subject.initHookForTests = new Runnable() { @Override public void run() { initCount.incrementAndGet(); }};
            Object handler = new Object();
            subject.handleConnected(handler);
            queue.runAll();
            Assert.assertEquals("takeover init must complete before disconnect", 1, initCount.get());
            cleanup.attempts.clear();

            subject.handleDisconnected(handler);
            Assert.assertEquals(1, queue.size());
            queue.runAll();

            Assert.assertEquals(FaultInjectingCleanup.expectedSteps(), cleanup.attempts);
            Assert.assertEquals("disconnect must not repeat init", 1, initCount.get());
            Assert.assertEquals("dispatcher drain must complete", 0, queue.size());
        }
    }

    /**
     * 并发：init callback 持锁期间 disconnect 须等待；异常路径外的顺序收敛。
     */
    @Test
    public void initHoldingMonitorBlocksDisconnectUntilComplete() throws Exception {
        final List<String> order = new ArrayList<String>();
        final Object orderLock = new Object();
        final CountDownLatch insideInit = new CountDownLatch(1);
        final CountDownLatch releaseInit = new CountDownLatch(1);
        final CountDownLatch disconnectDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        listener.initHookForTests = new Runnable() {
            @Override
            public void run() {
                synchronized (orderLock) {
                    order.add("init-enter");
                }
                insideInit.countDown();
                try {
                    Assert.assertTrue(releaseInit.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                synchronized (orderLock) {
                    order.add("init-exit");
                }
                inits.incrementAndGet();
            }
        };
        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                // takeover 先于 init；本测关注 init 持锁
            }
        };

        listener.handleConnected(handlerA);
        Assert.assertEquals(1, dispatcher.size());

        Thread mainDrain = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatcher.runAll();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "init-drain");
        mainDrain.start();
        Assert.assertTrue(insideInit.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 经 Listener 真实入口：须等 init 释放 monitor 后才能 transition
                    listener.handleDisconnected(handlerA);
                    synchronized (orderLock) {
                        order.add("disconnect-scheduled");
                    }
                    disconnectDone.countDown();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "disconnect-waiter");
        disconnectThread.start();

        Assert.assertFalse(
                "disconnect must wait while init holds lifecycle monitor",
                disconnectDone.await(200L, TimeUnit.MILLISECONDS));

        releaseInit.countDown();
        Assert.assertTrue(disconnectDone.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mainDrain.join(LATCH_TIMEOUT_MS);
        disconnectThread.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(mainDrain.isAlive());
        Assert.assertFalse(disconnectThread.isAlive());
        rethrow(error.get());

        synchronized (orderLock) {
            Assert.assertEquals(3, order.size());
            Assert.assertEquals("init-enter", order.get(0));
            Assert.assertEquals("init-exit", order.get(1));
            Assert.assertEquals("disconnect-scheduled", order.get(2));
        }
        // disconnect 已排队 cleanup
        Assert.assertEquals(1, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());
    }

    /**
     * null handler connect 不排队。
     */
    @Test
    public void nullHandlerConnectDoesNotEnqueue() {
        listener.handleConnected(null);
        Assert.assertEquals(0, dispatcher.size());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());
    }

    private static void rethrow(Throwable t) throws Exception {
        if (t == null) {
            return;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        throw new RuntimeException(t);
    }

    /**
     * 可注入主线程队列：记录排队次数并支持确定性 drain。
     */
    static final class QueueDispatcher implements ClientConnectionListener.TaskDispatcher {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void run(Runnable task) {
            if (task == null) {
                return;
            }
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            if (tasks.isEmpty()) {
                throw new AssertionError("no queued task");
            }
            tasks.remove(0).run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }

    /** 按名称在六个真实清理边界之一注入故障，并记录 listener 实际尝试顺序。 */
    static final class FaultInjectingCleanup implements ClientConnectionListener.CleanupActions {
        static final String[] STEPS = {"object-group", "auto-tool", "preview", "renderer", "phase", "pending"};
        private final String failedStep;
        private final boolean linkageError;
        final List<String> attempts = new ArrayList<String>();

        FaultInjectingCleanup(String failedStep, boolean linkageError) {
            this.failedStep = failedStep;
            this.linkageError = linkageError;
        }
        static List<String> expectedSteps() {
            List<String> result = new ArrayList<String>();
            for (String step : STEPS) result.add(step);
            return result;
        }
        private void runStep(String step) {
            attempts.add(step);
            if (step.equals(failedStep)) {
                if (linkageError) throw new LinkageError("cleanup-step-" + failedStep);
                throw new IllegalStateException("cleanup-step-" + failedStep);
            }
        }
        @Override public void clearObjectGroupPending() { runStep("object-group"); }
        @Override public void resetAutoTool() { runStep("auto-tool"); }
        @Override public void stopPreviewTask() { runStep("preview"); }
        @Override public void disposeRenderer() { runStep("renderer"); }
        @Override public void clearPhase() { runStep("phase"); }
        @Override public void clearEventPending() { runStep("pending"); }
    }
}
