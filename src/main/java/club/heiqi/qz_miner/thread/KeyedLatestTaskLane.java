package club.heiqi.qz_miner.thread;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 与普通 FIFO 完全隔离的 keyed latest-wins 任务泳道。
 *
 * <p>同一 key 仅占一个排队槽；新 key 在容量满时拒绝，已有 key 的更新始终允许。
 * start/stop 使用不可复用的 lane identity 隔离旧生命周期；关闭后清空并拒绝旧提交。
 * drain 每次最多消费 {@code drainBudget} 个槽，并保证生产停止后已接受值最终可达。</p>
 *
 * @param <K> 槽位键类型
 */
public final class KeyedLatestTaskLane<K> {

    /** 默认最大同时挂起 key 数。 */
    public static final int DEFAULT_CAPACITY = 256;

    /** 默认单次 drain 预算。 */
    public static final int DEFAULT_DRAIN_BUDGET = 64;

    private final int capacity;
    private final int drainBudget;
    private final AtomicLong nextIdentity = new AtomicLong(1L);
    private final AtomicReference<LaneState<K>> state = new AtomicReference<LaneState<K>>(LaneState.<K>closed());

    /**
     * 使用默认容量与 drain 预算。
     */
    public KeyedLatestTaskLane() {
        this(DEFAULT_CAPACITY, DEFAULT_DRAIN_BUDGET);
    }

    /**
     * @param capacity    最大同时挂起 key 数（须 &gt; 0）
     * @param drainBudget 单次 drain 最多消费槽位数（须 &gt; 0）
     */
    public KeyedLatestTaskLane(int capacity, int drainBudget) {
        if (capacity <= 0 || drainBudget <= 0) {
            throw new IllegalArgumentException("capacity/drainBudget must be positive");
        }
        this.capacity = capacity;
        this.drainBudget = drainBudget;
    }

    /**
     * 开启新生命周期，分配不可复用 identity；旧泳道立即失效。
     *
     * @return 新 lane identity（恒为正）
     */
    public long start() {
        long identity = nextIdentity.getAndIncrement();
        if (identity <= 0L) {
            // 极端溢出：跳过 0，保持正 identity
            identity = nextIdentity.incrementAndGet();
            if (identity <= 0L) {
                identity = 1L;
                nextIdentity.set(2L);
            }
        }
        state.set(new LaneState<K>(identity));
        return identity;
    }

    /**
     * 关闭当前泳道：清空挂起槽并拒绝后续提交。
     */
    public void stop() {
        LaneState<K> previous = state.getAndSet(LaneState.<K>closed());
        if (previous != null && previous.identity != 0L) {
            previous.pending.clear();
            previous.size.set(0);
        }
    }

    /**
     * 提交或更新 key 对应的最新任务。
     *
     * @param key  槽位键
     * @param task 最新任务
     * @return 已接受（含更新已有 key）时为 true；关闭、空参或新 key 容量满时为 false
     */
    public boolean submit(K key, Runnable task) {
        if (key == null || task == null) {
            return false;
        }
        while (true) {
            LaneState<K> current = state.get();
            if (current == null || current.identity == 0L) {
                return false;
            }
            AtomicReference<Runnable> existing = current.pending.get(key);
            if (existing != null) {
                existing.set(task);
                return state.get() == current;
            }
            int size = current.size.get();
            if (size >= capacity) {
                existing = current.pending.get(key);
                if (existing != null) {
                    existing.set(task);
                    return state.get() == current;
                }
                return false;
            }
            if (!current.size.compareAndSet(size, size + 1)) {
                continue;
            }
            AtomicReference<Runnable> created = new AtomicReference<Runnable>(task);
            AtomicReference<Runnable> raced = current.pending.putIfAbsent(key, created);
            if (raced != null) {
                current.size.decrementAndGet();
                raced.set(task);
                return state.get() == current;
            }
            if (state.get() != current) {
                // 旧 identity 已停；槽位随旧 map 一并废弃
                return false;
            }
            return true;
        }
    }

    /**
     * 消费至多 {@code drainBudget} 个槽并执行其最新任务。
     *
     * @return 实际执行的任务数
     */
    public int drain() {
        return drain(null);
    }

    /**
     * 消费至多 {@code drainBudget} 个槽并执行其最新任务。
     *
     * @param errors 可选运行时异常处理器；为 null 时吞掉并继续
     * @return 实际尝试执行的任务数
     */
    public int drain(ErrorHandler errors) {
        LaneState<K> current = state.get();
        if (current == null || current.identity == 0L) {
            return 0;
        }
        int drained = 0;
        Iterator<K> keys = current.pending.keySet().iterator();
        while (drained < drainBudget && keys.hasNext()) {
            if (state.get() != current) {
                break;
            }
            K key = keys.next();
            AtomicReference<Runnable> slot = current.pending.remove(key);
            if (slot == null) {
                continue;
            }
            current.size.decrementAndGet();
            Runnable task = slot.getAndSet(null);
            if (task == null) {
                continue;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                if (errors != null) {
                    errors.onError(e);
                }
            }
            drained++;
        }
        return drained;
    }

    /** @return 当前是否处于开放生命周期 */
    public boolean isOpen() {
        LaneState<K> current = state.get();
        return current != null && current.identity != 0L;
    }

    /** @return 当前 lane identity；关闭时为 0 */
    public long identity() {
        LaneState<K> current = state.get();
        return current == null ? 0L : current.identity;
    }

    /** @return 当前挂起 key 数（并发下近似） */
    public int pendingCount() {
        LaneState<K> current = state.get();
        return current == null ? 0 : Math.max(0, current.size.get());
    }

    /** @return 固定容量 */
    public int capacity() {
        return capacity;
    }

    /** @return 单次 drain 预算 */
    public int drainBudget() {
        return drainBudget;
    }

    /** drain 时运行时异常回调。 */
    public interface ErrorHandler {
        /**
         * @param error 任务抛出的运行时异常
         */
        void onError(RuntimeException error);
    }

    private static final class LaneState<K> {
        private static final LaneState<?> CLOSED = new LaneState<Object>(0L);

        @SuppressWarnings("unchecked")
        private static <K> LaneState<K> closed() {
            return (LaneState<K>) CLOSED;
        }

        private final long identity;
        private final ConcurrentHashMap<K, AtomicReference<Runnable>> pending =
                new ConcurrentHashMap<K, AtomicReference<Runnable>>();
        private final AtomicInteger size = new AtomicInteger();

        private LaneState(long identity) {
            this.identity = identity;
        }
    }
}
