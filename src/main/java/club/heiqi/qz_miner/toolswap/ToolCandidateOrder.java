package club.heiqi.qz_miner.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 选择器优先、槽位次序兜底的稳定候选排序。
 */
public final class ToolCandidateOrder {

    private ToolCandidateOrder() {
    }

    /**
     * 仅重排合格候选；同 selector 内与未命中 fallback 均按槽 0..35。
     *
     * @param candidates 候选快照
     * @param selectors 有序选择器
     * @return 新的不可变有序列表
     */
    public static List<ToolCandidate> sort(List<ToolCandidate> candidates, final List<ToolSelector> selectors) {
        List<ToolCandidate> eligible = new ArrayList<ToolCandidate>();
        if (candidates != null) {
            for (ToolCandidate candidate : candidates) {
                if (candidate != null && candidate.isEligibleForSwap()) {
                    eligible.add(candidate);
                }
            }
        }
        final List<ToolSelector> priorities = selectors == null
                ? Collections.<ToolSelector>emptyList() : selectors;
        Collections.sort(eligible, new Comparator<ToolCandidate>() {
            @Override
            public int compare(ToolCandidate left, ToolCandidate right) {
                int priority = Integer.compare(priority(left, priorities), priority(right, priorities));
                return priority != 0 ? priority : Integer.compare(left.slot(), right.slot());
            }
        });
        return Collections.unmodifiableList(eligible);
    }

    private static int priority(ToolCandidate candidate, List<ToolSelector> selectors) {
        for (int i = 0; i < selectors.size(); i++) {
            if (selectors.get(i).matches(candidate)) {
                return i;
            }
        }
        return selectors.size();
    }
}
