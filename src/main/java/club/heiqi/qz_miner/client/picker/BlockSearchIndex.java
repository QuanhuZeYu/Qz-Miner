package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 不可变、预归一化的方块候选搜索索引。 */
public final class BlockSearchIndex {
    private final List<Entry> entries;

    public BlockSearchIndex(List<BlockCandidate> candidates) {
        List<Entry> copy = new ArrayList<Entry>();
        for (BlockCandidate candidate : candidates) copy.add(new Entry(candidate));
        this.entries = Collections.unmodifiableList(copy);
    }

    /** 空查询返回空；非空查询按确定性相关度排序，最多保留 64 项及一项截断探针。 */
    public Result search(String query, int requestedLimit) {
        String normalized = normalize(query);
        if (normalized.isEmpty() || requestedLimit <= 0) return new Result(Collections.<BlockCandidate>emptyList(), false);
        int limit = Math.min(65, requestedLimit);
        List<Match> matches = new ArrayList<Match>();
        for (Entry entry : entries) {
            int rank = entry.rank(normalized);
            if (rank >= 0) matches.add(new Match(entry, rank));
        }
        Collections.sort(matches, Comparator.comparingInt((Match match) -> match.rank)
                .thenComparing(match -> match.entry.registry));
        List<BlockCandidate> result = new ArrayList<BlockCandidate>();
        for (int i = 0; i < matches.size() && i < limit; i++) result.add(matches.get(i).entry.candidate);
        return new Result(result, matches.size() > limit);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        private final BlockCandidate candidate;
        private final String registry;
        private final String localized;
        private final String variants;

        private Entry(BlockCandidate candidate) {
            this.candidate = candidate;
            registry = normalize(candidate.registry());
            localized = normalize(candidate.localizedName());
            StringBuilder names = new StringBuilder();
            for (BlockVariant variant : candidate.variants()) names.append('\n').append(normalize(variant.name()));
            variants = names.toString();
        }

        private int rank(String query) {
            if (registry.equals(query)) return 0;
            if (registry.startsWith(query)) return 1;
            if (localized.startsWith(query)) return 2;
            if (localized.contains(query) || variants.contains(query)) return 3;
            if (registry.contains(query)) return 4;
            return -1;
        }
    }

    private static final class Match {
        private final Entry entry;
        private final int rank;
        private Match(Entry entry, int rank) { this.entry = entry; this.rank = rank; }
    }

    /** 搜索结果及截断标记。 */
    public static final class Result {
        private final List<BlockCandidate> candidates;
        private final boolean truncated;
        private Result(List<BlockCandidate> candidates, boolean truncated) {
            this.candidates = Collections.unmodifiableList(new ArrayList<BlockCandidate>(candidates));
            this.truncated = truncated;
        }
        public List<BlockCandidate> candidates() { return candidates; }
        public boolean truncated() { return truncated; }
    }
}
