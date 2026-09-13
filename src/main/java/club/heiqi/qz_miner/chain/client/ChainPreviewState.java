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

    public int begin(ChainTarget origin) {
        RenderChange change;
        int startedGeneration;
        synchronized (renderStateLock) {
            this.origin = origin;
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

    public boolean addPreviewTarget(int expectedGeneration, ChainTarget target) {
        RenderChange change;
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            previewTargetSet.add(target);
            targetHead = new TargetNode(target, targetHead);
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
     * O(1) 捕获不可变持久链；renderer 每帧不得调用本方法。
     */
    public RenderSnapshot captureRenderSnapshot() {
        synchronized (renderStateLock) {
            return new RenderSnapshot(
                generation,
                renderRevision,
                active,
                targetHead,
                targetCount);
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

        private RenderSnapshot(
                int generation, long revision, boolean active, TargetNode targetHead, int targetCount) {
            this.generation = generation;
            this.revision = revision;
            this.active = active;
            this.targetHead = targetHead;
            this.targetCount = targetCount;
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

        private TargetNode(ChainTarget target, TargetNode previous) {
            this.target = target;
            this.previous = previous;
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
