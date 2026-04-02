package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 客户端预览状态。
 */
public class ChainPreviewState {

    private final List<ChainTarget> previewTargets = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger scannedCount = new AtomicInteger();
    private final AtomicInteger matchedCount = new AtomicInteger();
    private volatile ChainTarget origin;
    private volatile boolean active;
    private volatile boolean completed;
    private volatile int generation;

    public void begin(ChainTarget origin) {
        this.origin = origin;
        this.active = true;
        this.completed = false;
        this.generation++;
        this.previewTargets.clear();
        this.scannedCount.set(0);
        this.matchedCount.set(0);
    }

    public void clear() {
        this.origin = null;
        this.active = false;
        this.completed = false;
        this.generation++;
        this.previewTargets.clear();
        this.scannedCount.set(0);
        this.matchedCount.set(0);
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

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public int incrementScannedCount() {
        return scannedCount.incrementAndGet();
    }

    public int getScannedCount() {
        return scannedCount.get();
    }

    public int getMatchedCount() {
        return matchedCount.get();
    }

    public void addPreviewTarget(ChainTarget target) {
        previewTargets.add(target);
        matchedCount.incrementAndGet();
    }

    public List<ChainTarget> getPreviewTargetsSnapshot() {
        synchronized (previewTargets) {
            return new ArrayList<>(previewTargets);
        }
    }
}
