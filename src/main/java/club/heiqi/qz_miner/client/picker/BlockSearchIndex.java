package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import club.heiqi.config.ui.editor.PickerQuery;

/**
 * 清单级搜索索引：只做归一化 + rank，返回「命中的清单下标」有序数组。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §1.7 D-2/D-3/D-7、§1.6(a)（搜索 lane 顺序 =
 * rank 升序 + registry 字典序 tie-break）；{@code team/P2-Miner-Provider-改造设计.md} §2.5。</p>
 *
 * <p><b>与旧形态的差别</b>（旧 {@code search(query, requestedLimit)} 返回 {@code Result(candidates, truncated)}）：</p>
 * <ul>
 *   <li>不再返回 {@code SearchPickerData}（D-2：转换职责移交窗口切片，见 {@link BlockPickerCandidateSource#page}）；</li>
 *   <li>不再有 {@code Math.min(65, requestedLimit)} 硬夹（D-7/A5）：命中数是<b>真值</b>，
 *       窗口与 truncated 由调用方按 {@code SearchPickerSpec.maxItems()} 决定；</li>
 *   <li>rank 表不变（0=registry 相等、1=modId 相等、2=registry 前缀、3=本地化名前缀、4=modId 前缀、
 *       5=本地化名或变体名包含、6=modId 包含、7=registry 包含），避免搜索行为静默漂移。</li>
 * </ul>
 *
 * <p><b>名字索引</b>：rank 3/5 需要本地化名与变体名，它们只在分片物化时可获得，因此名字索引
 * （{@link BlockPickerNameIndex}）在<b>首个文本查询</b>时一次性构建并缓存；浏览 lane 与 {@code exact}
 * 永不触发该构建。索引在清单代际内不变 ⇒ 同一 {@code (query, 代际)} 命中序稳定（A-02/A-03）。</p>
 */
public final class BlockSearchIndex {

    private final BlockRegistrySnapshot snapshot;
    private final BlockVariantShardCache shards;
    private final String[] normalizedRegistries;
    private final String[] normalizedModIds;
    private BlockPickerNameIndex names;

    /**
     * @param snapshot 清单快照（索引生命周期 = 该快照的代际）
     * @param shards   分片缓存（构建名字索引时复用其物化结果）
     */
    public BlockSearchIndex(BlockRegistrySnapshot snapshot, BlockVariantShardCache shards) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (shards == null) {
            throw new IllegalArgumentException("shards must not be null");
        }
        this.snapshot = snapshot;
        this.shards = shards;
        int size = snapshot.size();
        this.normalizedRegistries = new String[size];
        this.normalizedModIds = new String[size];
        for (int i = 0; i < size; i++) {
            normalizedRegistries[i] = BlockPickerNameIndex.normalize(snapshot.registry(i));
            normalizedModIds[i] = BlockPickerNameIndex.normalize(snapshot.modId(i));
        }
    }

    /**
     * 命中序（清单下标数组）。
     *
     * @param query 查询条件（非 null）
     * @return {@code null} = 清单恒等序（浏览 lane 且无分类过滤，调用方零分配直接按下标切片）；
     *         否则命中下标：浏览 + 分类 = 清单序的子序列，文本 = rank 序 + registry 字典序 tie-break
     */
    public int[] orderFor(PickerQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.isBrowse()) {
            if (!query.hasCategoryFilter()) {
                return null;
            }
            return categoryOrder(query.categoryKey());
        }
        return rankedOrder(query);
    }

    /** 名字代际（语言/资源包）变化：丢弃名字索引与归一化结果之外的缓存，下次文本查询重建。 */
    public void invalidateNames() {
        names = null;
    }

    /** @return 名字索引是否已构建（诊断/测试探针） */
    public boolean hasNames() {
        return names != null;
    }

    private int[] categoryOrder(String categoryKey) {
        List<Integer> matches = new ArrayList<Integer>();
        for (int i = 0; i < normalizedModIds.length; i++) {
            if (normalizedModIds[i].equals(categoryKey)) {
                matches.add(Integer.valueOf(i));
            }
        }
        return toArray(matches);
    }

    private int[] rankedOrder(PickerQuery query) {
        String text = query.normalizedText();
        String category = query.hasCategoryFilter() ? query.categoryKey() : null;
        BlockPickerNameIndex nameIndex = names();
        List<Hit> matches = new ArrayList<Hit>();
        for (int i = 0; i < normalizedRegistries.length; i++) {
            if (category != null && !normalizedModIds[i].equals(category)) {
                continue;
            }
            int rank = rank(i, text, nameIndex);
            if (rank >= 0) {
                matches.add(new Hit(i, rank));
            }
        }
        Collections.sort(matches, Comparator.comparingInt((Hit hit) -> hit.rank)
                .thenComparing(hit -> normalizedRegistries[hit.index]));
        int[] order = new int[matches.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = matches.get(i).index;
        }
        return order;
    }

    /** 8 级 rank（与旧 {@code BlockSearchIndex.Entry.rank} 逐条同序，避免搜索行为漂移）。 */
    private int rank(int index, String text, BlockPickerNameIndex nameIndex) {
        if (normalizedRegistries[index].equals(text)) {
            return 0;
        }
        if (normalizedModIds[index].equals(text)) {
            return 1;
        }
        if (normalizedRegistries[index].startsWith(text)) {
            return 2;
        }
        String localized = nameIndex.localName(index);
        if (localized.startsWith(text)) {
            return 3;
        }
        if (normalizedModIds[index].startsWith(text)) {
            return 4;
        }
        if (localized.contains(text) || nameIndex.variantNames(index).contains(text)) {
            return 5;
        }
        if (normalizedModIds[index].contains(text)) {
            return 6;
        }
        if (normalizedRegistries[index].contains(text)) {
            return 7;
        }
        return -1;
    }

    private BlockPickerNameIndex names() {
        if (names == null) {
            names = BlockPickerNameIndex.build(snapshot, shards);
        }
        return names;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).intValue();
        }
        return result;
    }

    /** 命中项（下标 + rank），排序用。 */
    private static final class Hit {
        private final int index;
        private final int rank;

        private Hit(int index, int rank) {
            this.index = index;
            this.rank = rank;
        }
    }
}
