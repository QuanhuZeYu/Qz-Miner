package club.heiqi.qz_miner.chain.client.verify;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/**
 * T31 波次 6 生产接线驱动 harness（ChainPreviewRenderCache 为包私有，经反射注入假调度器驱动）。
 *
 * <p>驱动语义与生产一致：注册观察者 → 状态变更 → 缓存调度 BuildTask → 用永不 yield 的控制对象
 * 跑完整分片 → 反射 {@code pollPublication()} 取发布结果。仅驱动不复制实现。</p>
 */
public final class VerifyRenderCacheHarness {

    /** 发布结果视图。 */
    public static final class Publication {

        public final ChainPreviewMesh mesh;
        public final int generation;
        public final long stateRevision;

        Publication(ChainPreviewMesh mesh, int generation, long stateRevision) {
            this.mesh = mesh;
            this.generation = generation;
            this.stateRevision = stateRevision;
        }
    }

    private final Object cache;
    private final ChainPreviewState state;
    private final Class<?> cacheType;
    private final List<ParallelTickTask> scheduled = new ArrayList<ParallelTickTask>();
    private int scheduledTotal;
    private long tickId;

    public VerifyRenderCacheHarness(ChainPreviewState state) {
        this(state, new ChainPreviewMeshBuilder());
    }

    public VerifyRenderCacheHarness(ChainPreviewState state, final ChainPreviewMeshBuilder builder) {
        this.state = state;
        try {
            cacheType = Class.forName("club.heiqi.qz_miner.chain.client.ChainPreviewRenderCache");
            Class<?> schedulerType =
                Class.forName("club.heiqi.qz_miner.chain.client.ChainPreviewRenderCache$TaskScheduler");
            Object scheduler = Proxy.newProxyInstance(
                schedulerType.getClassLoader(), new Class<?>[] {schedulerType}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        if ("schedule".equals(method.getName()) && arguments != null && arguments.length == 1) {
                            scheduled.add((ParallelTickTask) arguments[0]);
                            scheduledTotal++;
                            return new ParallelTickSubscription() {
                                @Override
                                public void unregister() {
                                    // 测试用：不实际注销
                                }
                            };
                        }
                        return null;
                    }
                });
            Constructor<?> constructor = cacheType.getDeclaredConstructor(
                ChainPreviewState.class, ChainPreviewMeshBuilder.class, schedulerType);
            constructor.setAccessible(true);
            cache = constructor.newInstance(state, builder, scheduler);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法构造 ChainPreviewRenderCache harness", failure);
        }
    }

    /** 注册到状态观察者（等价生产 observeState）。 */
    public void observe() {
        invoke("observeState");
    }

    /** 注入视觉设置快照（等价生产 1 Hz 采样路径；内部字段名变更需同步此处）。 */
    public void setVisualSettings(club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings settings) {
        try {
            java.lang.reflect.Field field = cacheType.getDeclaredField("visualSettings");
            field.setAccessible(true);
            field.set(cache, settings);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注入 visualSettings", failure);
        }
    }

    /** @return 被驱动的缓存实例（仅用于反射读取内部状态，测试包内使用）。 */
    public Object cache() {
        return cache;
    }

    /** @return 累计被调度的分片次数（用于断言「无待构建工作不得产生空转任务」）。 */
    public int scheduledTotal() {
        return scheduledTotal;
    }

    /** 跑完全部已调度分片（永不 yield，等价构建线程跑完整窗口）。 */
    public int runScheduled() {
        List<ParallelTickTask> pending = new ArrayList<ParallelTickTask>(scheduled);
        scheduled.clear();
        int executed = 0;
        for (ParallelTickTask task : pending) {
            ParallelTaskResult result;
            try {
                result = task.run(new Control(++tickId));
            } catch (Exception failure) {
                throw new IllegalStateException("分片执行失败", failure);
            }
            executed++;
            if (result == ParallelTaskResult.YIELDED) {
                scheduled.add(task);
            }
        }
        return executed;
    }

    /** 跑分片直到出现发布（或达到上限），返回执行次数。 */
    public int runUntilPublication(int maxRounds) {
        int executed = 0;
        for (int round = 0; round < maxRounds; round++) {
            executed += runScheduled();
            if (peekPublication() != null) {
                return executed;
            }
            if (scheduled.isEmpty()) {
                return executed;
            }
        }
        return executed;
    }

    /** 取走发布（消费语义与生产一致：内部单槽被清空）。 */
    public Publication pollPublication() {
        Object publication = invoke("pollPublication");
        return publication == null ? null : wrap(publication);
    }

    /** 只读查看是否有待消费发布（不消费）。 */
    public Publication peekPublication() {
        try {
            java.lang.reflect.Field field =
                cacheType.getDeclaredField("pendingPublication");
            field.setAccessible(true);
            Object reference = field.get(cache);
            Object publication = ((java.util.concurrent.atomic.AtomicReference<?>) reference).get();
            return publication == null ? null : wrap(publication);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取 pendingPublication", failure);
        }
    }

    public ChainPreviewState state() {
        return state;
    }

    private Publication wrap(Object publication) {
        try {
            Class<?> type = publication.getClass();
            Method mesh = type.getDeclaredMethod("getMesh");
            mesh.setAccessible(true);
            Method generation = type.getDeclaredMethod("getGeneration");
            generation.setAccessible(true);
            Method revision = type.getDeclaredMethod("getStateRevision");
            revision.setAccessible(true);
            return new Publication(
                (ChainPreviewMesh) mesh.invoke(publication),
                ((Integer) generation.invoke(publication)).intValue(),
                ((Long) revision.invoke(publication)).longValue());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取 MeshPublication", failure);
        }
    }

    private Object invoke(String name) {
        try {
            Method method = cacheType.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(cache);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法调用 ChainPreviewRenderCache." + name, failure);
        }
    }

    /** 永不 yield 的控制对象：让分片一次跑完。 */
    private static final class Control implements ParallelTickControl {

        private final long tick;

        Control(long tick) {
            this.tick = tick;
        }

        @Override
        public long getTickId() {
            return tick;
        }

        @Override
        public ParallelTickStage getStage() {
            return ParallelTickStage.CLIENT_POST;
        }

        @Override
        public boolean isWindowOpen() {
            return true;
        }

        @Override
        public boolean isCancelRequested() {
            return false;
        }

        @Override
        public boolean shouldYield() {
            return false;
        }

        @Override
        public long getElapsedNanoTime() {
            return 0L;
        }

        @Override
        public String getCancelReason() {
            return "";
        }
    }
}
