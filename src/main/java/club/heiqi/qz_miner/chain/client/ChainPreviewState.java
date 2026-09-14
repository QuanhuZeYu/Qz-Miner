package club.heiqi.qz_miner.chain.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 客户端预览状态。
 */
public class ChainPreviewState {

    private final Object renderStateLock = new Object();
    private final Set<ChainTarget> previewTargetSet = new HashSet<ChainTarget>();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<Observer>();
    private final AtomicInteger scannedCount = new AtomicInteger();
    private final AtomicInteger matchedCount = new AtomicInteger();
    private volatile ChainTarget origin;
    private volatile boolean active;
    private volatile boolean completed;
    private volatile int generation;
    private long renderRevision;
    private TargetNode targetHead;
    private int targetCount;
    private volatile TruncationReason truncationReason = TruncationReason.NONE;
    private volatile int truncatedCount;
    private volatile int totalCount;
    private volatile CancelReason cancelReason = CancelReason.NONE;
    private int currentSemanticClass = ChainPreviewSemanticClass.UNDEFINED;

    /** @param origin 预览原点 @return 新代编号（类别记 {@link ChainPreviewSemanticClass#UNDEFINED}） */
    public int begin(ChainTarget origin) {
        return begin(origin, ChainPreviewSemanticClass.UNDEFINED);
    }

    /**
     * 开始新代并设定代内语义类别（B2.3 a 部分）。
     *
     * <p>代内后续 {@link #addPreviewTarget(int, ChainTarget)} 追加的目标都记录该类别；
     * 跨代重置：新代开始时类别上下文与类别数组随目标链一同重建，不累积。</p>
     *
     * @param origin 预览原点
     * @param semanticClass 本代类别（语义类别表冻结 id；非法值归 UNDEFINED）
     * @return 新代编号
     */
    public int begin(ChainTarget origin, int semanticClass) {
        RenderChange change;
        int startedGeneration;
        synchronized (renderStateLock) {
            this.origin = origin;
            this.currentSemanticClass = ChainPreviewSemanticClass.normalize(semanticClass);
            this.active = true;
            this.completed = false;
            this.generation++;
            this.previewTargetSet.clear();
            this.targetHead = null;
            this.targetCount = 0;
            this.scannedCount.set(0);
            this.matchedCount.set(0);
            this.truncationReason = TruncationReason.NONE;
            this.truncatedCount = 0;
            this.totalCount = 0;
            this.cancelReason = CancelReason.NONE;
            this.renderRevision++;
            startedGeneration = this.generation;
            change = currentChangeLocked();
        }
        publish(change);
        return startedGeneration;
    }

    public void clear() {
        RenderChange change;
        synchronized (renderStateLock) {
            this.origin = null;
            this.active = false;
            this.completed = false;
            this.generation++;
            this.previewTargetSet.clear();
            this.targetHead = null;
            this.targetCount = 0;
            this.scannedCount.set(0);
            this.matchedCount.set(0);
            this.truncationReason = TruncationReason.NONE;
            this.truncatedCount = 0;
            this.totalCount = 0;
            this.cancelReason = CancelReason.NONE;
            this.currentSemanticClass = ChainPreviewSemanticClass.UNDEFINED;
            this.renderRevision++;
            change = currentChangeLocked();
        }
        publish(change);
    }

    public int getGeneration() {
        return generation;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean setCompleted(int expectedGeneration, boolean completed) {
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            this.completed = completed;
            return true;
        }
    }

    public int incrementScannedCount(int expectedGeneration) {
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return -1;
            }
            return scannedCount.incrementAndGet();
        }
    }

    public int getScannedCount() {
        return scannedCount.get();
    }

    public int getMatchedCount() {
        return matchedCount.get();
    }

    /**
     * @return 本代按坐标去重的唯一目标数（{@code previewTargetSet.size()}）；与
     *         {@link #getMatchedCount()}（每次 add 的读取数，含重复坐标）对照，可判定
     *         「61 次 matched 是否落在少数唯一坐标」这一真机分流口径
     */
    public int getUniqueTargetCount() {
        synchronized (renderStateLock) {
            return previewTargetSet.size();
        }
    }

    /** @return 本代目标被上限截断的原因；NONE 表示未发生上限截断 */
    public TruncationReason getTruncationReason() {
        return truncationReason;
    }

    /** @return 已知被上限挡在预览之外的目标数；0 表示未截断，或截断数量未知（只有下界） */
    public int getTruncatedCount() {
        return truncatedCount;
    }

    /** @return 本代已知目标总数（含被截断）；未截断时等于已接收目标数 */
    public int getTotalCount() {
        return totalCount;
    }

    /** @return 本代预览的失败取消原因；NONE 表示未因远端失败/超时取消 */
    public CancelReason getCancelReason() {
        return cancelReason;
    }

    /**
     * 代内切换语义类别：只影响此后新增的目标，已记录目标类别不变；跨代由 {@link #begin} 重置。
     *
     * @param expectedGeneration 上报者持有的代
     * @param semanticClass 新类别（语义类别表冻结 id；非法值归 UNDEFINED）
     * @return 是否被本代接受
     */
    public boolean setSemanticClass(int expectedGeneration, int semanticClass) {
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            this.currentSemanticClass = ChainPreviewSemanticClass.normalize(semanticClass);
            return true;
        }
    }

    public boolean addPreviewTarget(int expectedGeneration, ChainTarget target) {
        RenderChange change;
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            previewTargetSet.add(target);
            targetHead = new TargetNode(target, targetHead, currentSemanticClass);
            targetCount++;
            if (targetCount > totalCount) {
                totalCount = targetCount;
            }
            matchedCount.incrementAndGet();
            renderRevision++;
            change = currentChangeLocked();
        }
        publish(change);
        return true;
    }

    /**
     * 记录本代被上限截断的事实。
     *
     * <p>截断不改变几何 revision：只更新只读访问器，供 HUD 与 B1.1 表现投影消费。</p>
     *
     * @param expectedGeneration 上报者持有的代
     * @param reason 截断原因；NONE 视为无效上报
     * @param truncatedCount 已知被截断目标数；未知传 0
     * @param totalCount 本代已知目标总数（含被截断）
     * @return 是否被本代接受
     */
    public boolean reportTruncation(
            int expectedGeneration, TruncationReason reason, int truncatedCount, int totalCount) {
        if (reason == null || reason == TruncationReason.NONE) {
            return false;
        }
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            this.truncationReason = reason;
            this.truncatedCount = Math.max(0, truncatedCount);
            this.totalCount = Math.max(this.totalCount, Math.max(0, totalCount));
            return true;
        }
    }

    /**
     * 以失败原因取消本代预览：保留已捕获目标，但不再活动，并通知 observer 取消。
     *
     * @param expectedGeneration 上报者持有的代
     * @param reason 取消原因
     * @return 是否被本代接受
     */
    public boolean cancelPreview(int expectedGeneration, CancelReason reason) {
        RenderChange change;
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            this.active = false;
            this.cancelReason = reason == null ? CancelReason.NONE : reason;
            this.renderRevision++;
            change = currentChangeLocked();
        }
        publish(change);
        return true;
    }

    /**
     * 捕获不可变持久链与同序类别数组；renderer 每帧不得调用本方法。
     *
     * <p>每次调用物化一份 {@code int[targetCount]} 类别数组（与 getTargets() 迭代顺序严格同序），
     * 供 worker 每次构建取用一次（构建频率，非渲染帧频率）。</p>
     */
    public RenderSnapshot captureRenderSnapshot() {
        synchronized (renderStateLock) {
            int[] semanticClasses = new int[targetCount];
            int index = 0;
            for (TargetNode node = targetHead; node != null && index < semanticClasses.length; node = node.previous) {
                semanticClasses[index++] = node.semanticClass;
            }
            return new RenderSnapshot(
                generation,
                renderRevision,
                active,
                targetHead,
                targetCount,
                semanticClasses);
        }
    }

    public boolean containsPreviewTarget(ChainTarget target) {
        synchronized (renderStateLock) {
            return previewTargetSet.contains(target);
        }
    }

    /** 注册轻量 observer，并立即投影当前 header；完整 target list 仅由 worker 主动捕获。 */
    public ObserverSubscription observe(Observer observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer");
        }
        RenderChange current;
        synchronized (renderStateLock) {
            observers.addIfAbsent(observer);
            current = currentChangeLocked();
        }
        observer.onPreviewChanged(current);
        return new ObserverSubscription() {
            @Override
            public void unsubscribe() {
                observers.remove(observer);
            }
        };
    }

    private RenderChange currentChangeLocked() {
        return new RenderChange(generation, renderRevision, active);
    }

    private void publish(RenderChange change) {
        for (Observer observer : observers) {
            try {
                observer.onPreviewChanged(change);
            } catch (RuntimeException failure) {
                observers.remove(observer);
                MyMod.LOG.warn("[ChainPreview] Detached failing render-state observer", failure);
            } catch (LinkageError failure) {
                observers.remove(observer);
                MyMod.LOG.warn("[ChainPreview] Detached incompatible render-state observer", failure);
            }
        }
    }

    /** 几何 change signal；不携带随 target 数量增长的 payload。 */
    public static final class RenderChange {

        private final int generation;
        private final long revision;
        private final boolean active;

        private RenderChange(int generation, long revision, boolean active) {
            this.generation = generation;
            this.revision = revision;
            this.active = active;
        }

        public int getGeneration() {
            return generation;
        }

        public long getRevision() {
            return revision;
        }

        public boolean isActive() {
            return active;
        }
    }

    /** CPU cache worker 消费的不可变核心快照。 */
    public static final class RenderSnapshot {

        private final int generation;
        private final long revision;
        private final boolean active;
        private final TargetNode targetHead;
        private final int targetCount;
        private final int[] semanticClasses;

        private RenderSnapshot(
                int generation,
                long revision,
                boolean active,
                TargetNode targetHead,
                int targetCount,
                int[] semanticClasses) {
            this.generation = generation;
            this.revision = revision;
            this.active = active;
            this.targetHead = targetHead;
            this.targetCount = targetCount;
            this.semanticClasses = semanticClasses == null ? new int[0] : semanticClasses;
        }

        public int getGeneration() {
            return generation;
        }

        public long getRevision() {
            return revision;
        }

        public boolean isActive() {
            return active;
        }

        /** @return snapshot 捕获时的目标数（包含上游重复值） */
        public int getTargetCount() {
            return targetCount;
        }

        /**
         * 与 {@link #getTargets()} 迭代顺序严格同序、等长的类别数组。
         *
         * <p>索引 i 对应第 i 个迭代目标（最新 target → 最早 target）；长度恒等于
         * {@link #getTargetCount()}。返回防御性拷贝，调用方可安全持有。</p>
         *
         * @return 语义类别表冻结的类别 id 数组
         */
        public int[] getSemanticClasses() {
            return semanticClasses.clone();
        }

        /**
         * @return 只读持久链；迭代顺序为最新 target 到最早 target，几何语义与顺序无关
         */
        public Iterable<ChainTarget> getTargets() {
            return new Iterable<ChainTarget>() {
                @Override
                public Iterator<ChainTarget> iterator() {
                    return new Iterator<ChainTarget>() {
                        private TargetNode cursor = targetHead;

                        @Override
                        public boolean hasNext() {
                            return cursor != null;
                        }

                        @Override
                        public ChainTarget next() {
                            if (cursor == null) {
                                throw new NoSuchElementException();
                            }
                            ChainTarget target = cursor.target;
                            cursor = cursor.previous;
                            return target;
                        }

                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException("immutable preview snapshot");
                        }
                    };
                }
            };
        }
    }

    private static final class TargetNode {

        private final ChainTarget target;
        private final TargetNode previous;
        private final int semanticClass;

        private TargetNode(ChainTarget target, TargetNode previous, int semanticClass) {
            this.target = target;
            this.previous = previous;
            this.semanticClass = semanticClass;
        }
    }

    /** 预览目标被上限截断的原因。 */
    public enum TruncationReason {
        /** 未发生上限截断。 */
        NONE,
        /** 达到 clientPreviewMaxTargets / 服务端 chainMaxBlocks 上限，可能仍有未探索目标。 */
        MAX_TARGETS,
        /** 达到 clientPreviewMaxTargetsHardCap 硬顶。 */
        HARD_CAP,
        /** 远端预览返回达到本次请求上限。 */
        REMOTE_LIMIT
    }

    /** 预览被失败取消的原因。 */
    public enum CancelReason {
        /** 未因失败取消。 */
        NONE,
        /** 远端预览在 clientPreviewRemoteTimeoutMs 内未返回。 */
        REMOTE_TIMEOUT,
        /** 远端预览 provider 不可用或拒绝请求。 */
        REMOTE_UNAVAILABLE
    }

    public interface Observer {
        void onPreviewChanged(RenderChange change);
    }

    public interface ObserverSubscription {
        void unsubscribe();
    }
}
