package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 连锁规划结果。
 */
public final class ChainPlan {

    private final ChainTarget origin;
    private final List<ChainTarget> targets;

    public ChainPlan(ChainTarget origin, List<ChainTarget> targets) {
        this.origin = origin;
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public List<ChainTarget> getTargets() {
        return targets;
    }
}
