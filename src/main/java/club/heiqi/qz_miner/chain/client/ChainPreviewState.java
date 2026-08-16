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

    public boolean addPreviewTarget(int expectedGeneration, ChainTarget target) {
        RenderChange change;
        synchronized (renderStateLock) {
            if (!active || generation != expectedGeneration) {
                return false;
            }
            previewTargetSet.add(target);
            targetHead = new TargetNode(target, targetHead);
            targetCount++;
            matchedCount.incrementAndGet();
            renderRevision++;
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

    public interface Observer {
        void onPreviewChanged(RenderChange change);
    }

    public interface ObserverSubscription {
        void unsubscribe();
    }
}
